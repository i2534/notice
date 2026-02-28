/**
 * Notice channel plugin for Openclaw.
 * - Receives messages by subscribing to MQTT.
 * - Sends messages by publishing to MQTT (same broker, no webhook).
 * - When replying with media (mediaUrl/mediaUrls), local file paths are uploaded to Notice server
 *   (POST /api/upload) and the returned image URLs are sent in content as markdown images.
 *
 * Config: channels.notice
 *   brokerUrl, token, topic (default notice/openclaw), serverUrl (optional, for image upload)
 */
import fs from "fs/promises";
import path from "path";
import type {
    ChannelConfigSchema,
    OpenClawPluginApi,
    PluginRuntime,
} from "openclaw/plugin-sdk";
import mqtt from "mqtt";

// ---------------------------------------------------------------------------
// 常量
// ---------------------------------------------------------------------------

const CHANNEL_ID = "notice";
const DEFAULT_TOPIC = "notice/openclaw";
const OUTBOUND_CLIENT_ID = "openclaw";
const NOTICE_MAX_CONTENT_LENGTH = 1024;
const MAX_RECENT_MESSAGES = 50;

// ---------------------------------------------------------------------------
// 配置与账号
// ---------------------------------------------------------------------------

function getChannelConfig(cfg: Record<string, unknown>): Record<string, unknown> | undefined {
    return (cfg?.channels as Record<string, unknown>)?.[CHANNEL_ID] as Record<string, unknown> | undefined;
}

function listAccountIds(cfg: Record<string, unknown>): string[] {
    const ch = getChannelConfig(cfg);
    if (!ch) return [];
    const accounts = ch.accounts as Record<string, unknown> | undefined;
    if (accounts && typeof accounts === "object") return Object.keys(accounts);
    return ["default"];
}

function resolveAccount(cfg: Record<string, unknown>, accountId: string | undefined): Record<string, unknown> {
    const ch = getChannelConfig(cfg);
    const id = accountId ?? "default";
    if (!ch) return { accountId: id };
    const accounts = ch.accounts as Record<string, Record<string, unknown>> | undefined;
    if (accounts?.[id]) return { accountId: id, ...accounts[id] };
    return { accountId: id, ...ch };
}

function getBrokerCredentials(cfg: Record<string, unknown>): { brokerUrl: string; token: string } | null {
    const ch = getChannelConfig(cfg);
    const acc = resolveAccount(cfg, "default");
    const brokerUrl = ((ch?.brokerUrl ?? acc?.brokerUrl) as string)?.trim();
    const token = ((ch?.token ?? acc?.token) as string)?.trim();
    if (!brokerUrl || !token) return null;
    return { brokerUrl, token };
}

/** Server HTTP base URL for /api/upload (e.g. https://notice.example.com). Optional. */
/** 仅当配置了 serverUrl 时返回，未配置则不能上传/拉取图片。不根据 brokerUrl 推导。 */
function getServerBaseUrl(cfg: Record<string, unknown>): string | null {
    const ch = getChannelConfig(cfg);
    const acc = resolveAccount(cfg, "default");
    const url = ((ch?.serverUrl ?? acc?.serverUrl) as string)?.trim() ?? "";
    if (!url) return null;
    return url.replace(/\/+$/, "");
}

// 模块级 MQTT 客户端，供订阅与发送共用
let mqttClient: mqtt.MqttClient | null = null;

// 插件 logger，在 register 时注入，供发送等逻辑打日志
type PluginLog = (msg: string, ...args: unknown[]) => void;
let pluginLogger: { info: PluginLog; warn: PluginLog } = {
    info: (msg, ...args) => console.log("[notice]", msg, ...args),
    warn: (msg, ...args) => console.warn("[notice]", msg, ...args),
};

/** 订阅主题转可发布主题（与 Notice Server topicForPublish 一致） */
function topicForPublish(topic: string): string {
    let t = topic.trim();
    const hashIndex = t.indexOf("#");
    if (hashIndex >= 0) {
        t = t.substring(0, hashIndex).trim().replace(/\/+$/, "");
        if (!t) t = "notice";
    }
    if (t.includes("+")) {
        t = t
            .split("/")
            .map((p) => (p === "+" ? "reply" : p))
            .join("/");
    }
    return t;
}

/** 通过 MQTT 发布一条消息（不经过 webhook） */
function sendViaMqtt(publishTopic: string, text: string, title?: string): Promise<{ ok: boolean; error?: string }> {
    const c = mqttClient;
    if (!c?.connected) {
        pluginLogger.warn("[notice] Send skipped: MQTT not connected");
        return Promise.resolve({ ok: false, error: "MQTT not connected" });
    }
    const payload = JSON.stringify({
        title: title ?? "Openclaw",
        content: text,
        client: OUTBOUND_CLIENT_ID,
        timestamp: Date.now(),
    });
    return new Promise((resolve) => {
        c.publish(publishTopic, payload, { qos: 1 }, (err) => {
            if (err) {
                pluginLogger.warn("[notice] Send failed topic=" + publishTopic + " error=" + String(err));
                resolve({ ok: false, error: String(err) });
            } else {
                pluginLogger.info("[notice] Sent topic=" + publishTopic + " length=" + text.length);
                resolve({ ok: true });
            }
        });
    });
}

/** Returns true if the string looks like a local path (no http(s) scheme). */
function isLocalPath(s: string): boolean {
    const t = s.trim();
    return t.length > 0 && !/^https?:\/\//i.test(t);
}

const IMAGE_EXT_REGEX = /\/[^\s:]+\.(?:png|jpe?g|gif|webp)/gi;

/** 从文本中提取可能是本地图片的绝对路径（如 /home/.../xxx.png）。 */
function extractLocalImagePathsFromText(text: string): string[] {
    if (!text || typeof text !== "string") return [];
    const out: string[] = [];
    let m: RegExpExecArray | null;
    IMAGE_EXT_REGEX.lastIndex = 0;
    while ((m = IMAGE_EXT_REGEX.exec(text)) !== null) {
        const p = m[0];
        if (isLocalPath(p) && !out.includes(p)) out.push(p);
    }
    return out;
}

/**
 * Upload local image files to Notice server (POST /api/upload).
 * Returns the image_urls from the response. Logs and skips non-existent paths.
 */
async function uploadImagesToNotice(
    serverBaseUrl: string,
    token: string,
    filePaths: string[],
    log: { info: PluginLog; warn: PluginLog }
): Promise<string[]> {
    const toUpload: { buffer: Buffer; filename: string }[] = [];
    for (const p of filePaths) {
        const resolved = path.isAbsolute(p) ? p : path.resolve(p);
        try {
            const buf = await fs.readFile(resolved);
            const ext = path.extname(resolved).toLowerCase();
            const allowed = [".jpg", ".jpeg", ".png", ".gif", ".webp"];
            if (!allowed.includes(ext)) {
                log.warn("[notice] Skip upload (disallowed ext) " + resolved);
                continue;
            }
            toUpload.push({
                buffer: buf,
                filename: path.basename(resolved) || "image" + ext,
            });
        } catch (e) {
            log.warn("[notice] Cannot read file for upload " + resolved + " " + String(e));
        }
    }
    if (toUpload.length === 0) return [];

    const form = new FormData();
    for (const { buffer, filename } of toUpload) {
        form.append("file", new Blob([new Uint8Array(buffer)]), filename);
    }
    const url = serverBaseUrl.replace(/\/+$/, "") + "/api/upload";
    try {
        const res = await fetch(url, {
            method: "POST",
            headers: { Authorization: "Bearer " + token },
            body: form,
        });
        const data = (await res.json()) as { success?: boolean; image_urls?: string[]; message?: string };
        if (!res.ok || !data.success || !Array.isArray(data.image_urls)) {
            log.warn("[notice] Upload failed status=" + res.status + " message=" + (data.message ?? ""));
            return [];
        }
        const urls = data.image_urls as string[];
        const fullUrls = urls.map((u) => (u.startsWith("http") ? u : serverBaseUrl.replace(/\/+$/, "") + (u.startsWith("/") ? u : "/" + u)));
        log.info("[notice] Uploaded " + toUpload.length + " images");
        return fullUrls;
    } catch (e) {
        log.warn("[notice] Upload request error " + String(e));
        return [];
    }
}

function chunkTextForNotice(text: string, maxLen: number = NOTICE_MAX_CONTENT_LENGTH): string[] {
    const t = text.trim();
    if (!t) return [];
    if (t.length <= maxLen) return [t];
    const chunks: string[] = [];
    let rest = t;
    while (rest.length > 0) {
        if (rest.length <= maxLen) {
            chunks.push(rest);
            break;
        }
        let slice = rest.slice(0, maxLen);
        const lastNewline = slice.lastIndexOf("\n");
        if (lastNewline > maxLen >> 1) slice = rest.slice(0, lastNewline + 1);
        chunks.push(slice.trim());
        rest = rest.slice(slice.length).trim();
    }
    return chunks;
}

async function sendReplyChunked(
    rawTopic: string,
    text: string,
    log: { warn: PluginLog }
): Promise<boolean> {
    const topic = topicForPublish(rawTopic);
    const chunks = chunkTextForNotice(text);
    for (const chunk of chunks) {
        const ok = await sendViaMqtt(topic, chunk);
        if (!ok.ok) {
            log.warn("[notice] Reply send failed " + (ok.error ?? ""));
            return false;
        }
    }
    pluginLogger.info("[notice] Reply sent topic=" + topic + " chunks=" + chunks.length);
    return true;
}

// ---------------------------------------------------------------------------
// 类型：直接引用 openclaw 官方 plugin-sdk
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Channel 定义
// ---------------------------------------------------------------------------

const noticeChannelConfigSchema: ChannelConfigSchema = {
    schema: {
        type: "object",
        additionalProperties: false,
        properties: {
            token: { type: "string" },
            brokerUrl: { type: "string" },
            topic: { type: "string", default: DEFAULT_TOPIC },
            serverUrl: { type: "string" },
            blockStreaming: { type: "boolean", default: true },
            blockStreamingBreak: {
                type: "string",
                enum: ["text_end", "message_end"],
                default: "text_end",
            },
        },
    },
    uiHints: {
        token: { label: "Token", sensitive: true },
        brokerUrl: { label: "MQTT Broker URL", placeholder: "wss://... or tcp://..." },
        topic: { label: "Topic (subscribe & publish)", placeholder: DEFAULT_TOPIC },
        serverUrl: { label: "Notice 服务器 URL (图片上传)", placeholder: "https://notice.example.com" },
        blockStreaming: {
            label: "按块流式发送",
            help: "启用后，Agent 回复在生成过程中按块通过 MQTT 发送；关闭则等整条消息结束后再发。",
        },
        blockStreamingBreak: {
            label: "发送时机",
            help: "text_end：每块产出即发送（逐条）；message_end：整条消息结束后再发送。",
        },
    },
};

const noticeChannel = {
    id: CHANNEL_ID,
    meta: {
        id: CHANNEL_ID,
        label: "Notice",
        selectionLabel: "Notice (MQTT push)",
        docsPath: "/channels/notice",
        blurb: "Send and receive messages via MQTT (Notice broker).",
        aliases: ["notice"],
    },
    capabilities: { chatTypes: ["direct"] as const },
    configSchema: noticeChannelConfigSchema,
    config: { listAccountIds, resolveAccount },
    gateway: { start: async () => { }, stop: async () => { } },
    onboarding: {
        channel: CHANNEL_ID,
        getStatus: async ({ cfg }: { cfg: Record<string, unknown> }) => {
            const creds = getBrokerCredentials(cfg);
            const configured = Boolean(creds);
            return {
                channel: CHANNEL_ID,
                configured,
                statusLines: configured
                    ? ["Notice: configured (broker URL, token, topic)"]
                    : ["Notice: needs broker URL and token"],
                selectionHint: configured ? "configured" : "needs setup",
                quickstartScore: configured ? 2 : 0,
            };
        },
        configure: async ({
            cfg,
            prompter,
        }: {
            cfg: Record<string, unknown>;
            prompter: {
                text: (opts: {
                    message: string;
                    placeholder?: string;
                    initialValue?: string;
                }) => Promise<string>;
            };
        }) => {
            const ch = (getChannelConfig(cfg) ?? {}) as Record<string, unknown>;
            const brokerUrl = (
                await prompter.text({
                    message: "MQTT broker URL",
                    placeholder: "wss://... or tcp://...",
                    initialValue: String(ch.brokerUrl ?? "").trim(),
                })
            ).trim();
            const token = (
                await prompter.text({
                    message: "Auth token",
                    placeholder: "your token",
                    initialValue: String(ch.token ?? "").trim(),
                })
            ).trim();
            const topic = (
                await prompter.text({
                    message: "MQTT topic (subscribe & publish)",
                    placeholder: DEFAULT_TOPIC,
                    initialValue: String(ch.topic ?? DEFAULT_TOPIC).trim(),
                })
            ).trim();
            const serverUrl = (
                await prompter.text({
                    message: "Notice 服务器 URL（图片上传，可选）",
                    placeholder: "https://notice.example.com",
                    initialValue: String(ch.serverUrl ?? "").trim(),
                })
            ).trim();
            return {
                cfg: {
                    ...cfg,
                    channels: {
                        ...(cfg.channels as Record<string, unknown>),
                        [CHANNEL_ID]: {
                            ...ch,
                            enabled: true,
                            token: token || (ch.token ?? ""),
                            brokerUrl: brokerUrl || (ch.brokerUrl ?? ""),
                            topic: topic || (ch.topic ?? DEFAULT_TOPIC),
                            serverUrl: serverUrl || (ch.serverUrl ?? ""),
                            blockStreaming: ch.blockStreaming ?? true,
                            blockStreamingBreak: ch.blockStreamingBreak ?? "text_end",
                        },
                    },
                },
                accountId: "default",
            };
        },
    },
    outbound: {
        deliveryMode: "direct" as const,
        sendText: async (ctx: {
            text: string;
            config?: Record<string, unknown>;
            accountId?: string;
            channel?: string;
            to?: string;
        }): Promise<{ ok: boolean; error?: string }> => {
            const cfg = ctx.config ?? {};
            const creds = getBrokerCredentials(cfg);
            if (!creds) return { ok: false, error: "Missing brokerUrl or token in channels.notice" };
            const acc = resolveAccount(cfg, ctx.accountId);
            const rawTopic =
                (typeof ctx.to === "string" && ctx.to.trim() ? ctx.to.trim() : null) ??
                (acc.topic as string) ??
                (getChannelConfig(cfg)?.topic as string) ??
                DEFAULT_TOPIC;
            const publishTopic = topicForPublish(rawTopic);
            const chunks = chunkTextForNotice(ctx.text);
            for (const chunk of chunks) {
                const ok = await sendViaMqtt(publishTopic, chunk);
                if (!ok.ok) return ok;
            }
            return { ok: true };
        },
    },
};

// ---------------------------------------------------------------------------
// 入站投递：dispatch / enqueue+wake / hook 三种路径
// ---------------------------------------------------------------------------

const recentMessages: Array<{ topic: string; payload: string; at: number }> = [];

function parseMqttMessage(
    payloadStr: string
): { messageText: string; contentPreview: string; skip: boolean } {
    let contentPreview = payloadStr.slice(0, 200);
    let messageText = payloadStr;
    try {
        const msg = JSON.parse(payloadStr) as { content?: string; title?: string; client?: string };
        if (msg.content === "__auth_check__") return { messageText: "", contentPreview: "", skip: true };
        if (msg.client === OUTBOUND_CLIENT_ID) return { messageText: "", contentPreview: "", skip: true };
        if (msg.content != null) {
            contentPreview = String(msg.content).slice(0, 200);
            messageText = String(msg.content);
        }
    } catch {
        /* non-JSON payload */
    }
    return { messageText, contentPreview: contentPreview.replace(/\s+/g, " ").trim(), skip: false };
}

function getHooksConfig(cfg: Record<string, unknown>): {
    port: number;
    path: string;
    enabled: boolean;
    token: string;
} {
    const hooks = cfg.hooks as { enabled?: boolean; token?: string; path?: string } | undefined;
    const port = (cfg.gateway as { port?: number } | undefined)?.port ?? 18789;
    const path = ((hooks?.path ?? "/hooks") as string).replace(/\/+$/, "");
    return {
        port,
        path,
        enabled: Boolean(hooks?.enabled && hooks?.token),
        token: String(hooks?.token ?? ""),
    };
}

function buildReplyText(payload: {
    text?: string;
    mediaUrl?: string;
    mediaUrls?: string[];
}): string {
    const mediaUrls = payload.mediaUrls?.length
        ? payload.mediaUrls
        : payload.mediaUrl
            ? [payload.mediaUrl]
            : [];
    const textPart = payload.text?.trim() ?? "";
    const mediaPart = mediaUrls.length ? mediaUrls.join("\n") : "";
    return textPart ? (mediaPart ? textPart + "\n\n" + mediaPart : textPart) : mediaPart;
}

/**
 * Resolve media payload: upload local file paths to Notice server, then build content
 * with text + markdown image links ![](url) for all image URLs.
 */
async function resolveMediaAndBuildContent(
    cfg: Record<string, unknown>,
    payload: { text?: string; mediaUrl?: string; mediaUrls?: string[] },
    log: { info: PluginLog; warn: PluginLog }
): Promise<string> {
    const fromPayload = payload.mediaUrls?.length ? payload.mediaUrls : payload.mediaUrl ? [payload.mediaUrl] : [];
    const fromText = extractLocalImagePathsFromText(payload.text ?? "");
    const allMediaRefs = [...fromPayload];
    for (const p of fromText) {
        if (!allMediaRefs.includes(p)) allMediaRefs.push(p);
    }
    const localPaths = allMediaRefs.filter(isLocalPath);
    const existingUrls = allMediaRefs.filter((u) => !isLocalPath(u));
    let uploaded: string[] = [];
    const serverBase = getServerBaseUrl(cfg);
    const token = ((getChannelConfig(cfg)?.token ?? resolveAccount(cfg, "default")?.token) as string)?.trim();
    if (localPaths.length > 0 && serverBase && token) {
        uploaded = await uploadImagesToNotice(serverBase, token, localPaths, log);
    } else if (localPaths.length > 0) {
        log.warn("[notice] Local image paths ignored (set serverUrl and token for upload)");
    }
    const allUrls = [...existingUrls, ...uploaded];
    const textPart = payload.text?.trim() ?? "";
    const mediaPart = allUrls.length ? allUrls.map((u) => "![](" + u + ")").join("\n") : "";
    return textPart ? (mediaPart ? textPart + "\n\n" + mediaPart : textPart) : mediaPart;
}

async function deliverInboundViaDispatch(
    api: OpenClawPluginApi,
    cfg: Record<string, unknown>,
    topicStr: string,
    messageTrimmed: string
): Promise<boolean> {
    const runtime = api.runtime as PluginRuntime | undefined;
    const reply = runtime?.channel?.reply;
    const routing = runtime?.channel?.routing;
    if (
        !reply?.dispatchReplyWithBufferedBlockDispatcher ||
        !reply?.formatAgentEnvelope ||
        !reply?.finalizeInboundContext ||
        !reply?.resolveEnvelopeFormatOptions ||
        !routing?.resolveAgentRoute
    ) {
        return false;
    }
    const route = routing.resolveAgentRoute({
        cfg,
        channel: CHANNEL_ID,
        accountId: "default",
        peer: { kind: "user", id: topicStr },
    });
    const envelope = reply.formatAgentEnvelope({
        channel: "Notice",
        from: "notice",
        timestamp: Date.now(),
        body: messageTrimmed,
        envelope: reply.resolveEnvelopeFormatOptions(cfg),
    });
    const ctxPayload = reply.finalizeInboundContext({
        Body: envelope,
        RawBody: messageTrimmed,
        CommandBody: messageTrimmed,
        From: "notice:" + topicStr,
        To: topicStr,
        SessionKey: route.sessionKey,
        AccountId: route.accountId,
        ChatType: "direct",
        ConversationLabel: topicStr,
        SenderName: "notice",
        SenderId: topicStr,
        Provider: CHANNEL_ID,
        Surface: CHANNEL_ID,
        OriginatingChannel: CHANNEL_ID,
        OriginatingTo: topicStr,
    });
    if (!mqttClient?.connected) {
        pluginLogger.warn("[notice] MQTT not connected, cannot deliver reply");
        return true; // consumed
    }
    pluginLogger.info("[notice] Dispatching to agent sessionKey=" + route.sessionKey);
    await reply.dispatchReplyWithBufferedBlockDispatcher({
        ctx: ctxPayload,
        cfg,
        dispatcherOptions: {
            deliver: async (payload) => {
                const text = await resolveMediaAndBuildContent(cfg, payload, pluginLogger);
                if (!text.trim()) return;
                await sendReplyChunked(topicStr, text, pluginLogger);
            },
        },
    });
    return true;
}

async function deliverInboundViaEnqueueAndWake(
    api: OpenClawPluginApi,
    cfg: Record<string, unknown>,
    topicStr: string,
    messageTrimmed: string,
    safePreview: string
): Promise<boolean> {
    const runtime = api.runtime as PluginRuntime | undefined;
    const enqueue = runtime?.system?.enqueueSystemEvent;
    const session = runtime?.channel?.session;
    if (!enqueue || !session?.resolveStorePath || !session?.updateLastRoute) return false;
    const sessionKey = "notice:" + topicStr;
    const storePath = session.resolveStorePath();
    await session.updateLastRoute({
        storePath,
        sessionKey,
        channel: CHANNEL_ID,
        to: topicStr,
    });
    enqueue(messageTrimmed, {
        sessionKey,
        contextKey: "notice:msg:" + topicStr + ":" + Date.now(),
    });
    pluginLogger.info("[notice] Enqueued sessionKey=" + sessionKey + ", sending wake");
    const hooks = getHooksConfig(cfg);
    if (hooks.enabled) {
        const res = await fetch(`http://127.0.0.1:${hooks.port}${hooks.path}/wake`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Authorization: "Bearer " + hooks.token,
            },
            body: JSON.stringify({ text: "Notice: " + safePreview.slice(0, 80), mode: "now" }),
        });
        if (!res.ok) pluginLogger.warn("[notice] Wake failed status=" + res.status + " body=" + (await res.text()));
    } else {
        pluginLogger.warn("[notice] Hooks disabled or no token, wake skipped");
    }
    return true;
}

async function deliverInboundViaHookAgent(
    api: OpenClawPluginApi,
    cfg: Record<string, unknown>,
    topicStr: string,
    messageTrimmed: string
): Promise<void> {
    const hooks = getHooksConfig(cfg);
    if (!hooks.enabled) {
        pluginLogger.warn("[notice] Hooks disabled or no token, cannot deliver to agent");
        return;
    }
    const sessionKey = "notice:" + topicStr;
    try {
        const res = await fetch(`http://127.0.0.1:${hooks.port}${hooks.path}/agent`, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Authorization: "Bearer " + hooks.token,
            },
            body: JSON.stringify({
                message: messageTrimmed,
                name: "Notice",
                sessionKey,
                deliver: true,
                channel: CHANNEL_ID,
                to: topicStr,
                wakeMode: "now",
            }),
        });
        if (res.ok) {
            pluginLogger.info("[notice] Hook agent started sessionKey=" + sessionKey);
        } else {
            pluginLogger.warn("[notice] Hook agent failed status=" + res.status + " body=" + (await res.text()));
        }
    } catch (e) {
        pluginLogger.warn("[notice] Hook agent error " + String(e));
    }
}

async function handleInboundMessage(
    api: OpenClawPluginApi,
    topicStr: string,
    payloadStr: string,
    messageText: string,
    contentPreview: string
): Promise<void> {
    const cfg = api.config ?? {};
    try {
        if (await deliverInboundViaDispatch(api, cfg, topicStr, messageText)) return;
        if (await deliverInboundViaEnqueueAndWake(api, cfg, topicStr, messageText, contentPreview)) return;
        await deliverInboundViaHookAgent(api, cfg, topicStr, messageText);
    } catch (e) {
        pluginLogger.warn("[notice] Inbound delivery error " + String(e));
    }
}

// ---------------------------------------------------------------------------
// 插件注册
// ---------------------------------------------------------------------------

export default function register(api: OpenClawPluginApi): void {
    const hostLogger = api.logger;
    pluginLogger = {
        info: (msg, ...args) => (hostLogger?.info ? hostLogger.info(msg, ...args) : console.log("[notice]", msg, ...args)),
        warn: (msg, ...args) => (hostLogger?.warn ? hostLogger.warn(msg, ...args) : console.warn("[notice]", msg, ...args)),
    };
    api.registerChannel({ plugin: noticeChannel });

    const cfg = api.config ?? {};
    const ch = getChannelConfig(cfg);
    const brokerUrl = (ch?.brokerUrl as string)?.trim();
    const token = ((ch?.token ?? resolveAccount(cfg, "default")?.token) as string)?.trim();
    const topic = (ch?.topic as string) ?? DEFAULT_TOPIC;

    if (!brokerUrl || !token) {
        pluginLogger.warn("[notice] brokerUrl or token not set, MQTT service not started; no receive/send.");
        if (!api.registerService) return;
    }

    if (brokerUrl && token && api.registerService) {
        api.registerService({
            id: "notice-mqtt",
            start: () => {
                pluginLogger.info("[notice] Connecting MQTT broker=" + brokerUrl + " topic=" + topic);
                const client = mqtt.connect(brokerUrl, {
                    username: token,
                    password: token,
                    clientId: "openclaw-" + Math.random().toString(16).slice(2, 10),
                    reconnectPeriod: 5000,
                });
                mqttClient = client;
                client.on("connect", () => {
                    pluginLogger.info("[notice] MQTT connected, subscribing to topic=" + topic);
                    client.subscribe(topic, (err) => {
                        if (err) pluginLogger.warn("[notice] Subscribe error: " + String(err));
                    });
                });
                client.on("message", async (t, payload) => {
                    const payloadStr = typeof payload === "string" ? payload : payload?.toString?.() ?? "";
                    const { messageText, contentPreview, skip } = parseMqttMessage(payloadStr);
                    if (skip) return;

                    recentMessages.unshift({ topic: t, payload: payloadStr, at: Date.now() });
                    if (recentMessages.length > MAX_RECENT_MESSAGES) recentMessages.pop();
                    pluginLogger.info("[notice] Message received topic=" + t + " content=" + contentPreview);

                    const trimmed = messageText.trim();
                    if (!trimmed) return;
                    await handleInboundMessage(api, String(t), payloadStr, trimmed, contentPreview);
                });
                client.on("error", (err) => pluginLogger.warn("[notice] MQTT error: " + String(err)));
            },
            stop: async () => {
                if (mqttClient) {
                    mqttClient.end();
                    mqttClient = null;
                }
            },
        });
    }

    if (api.registerGatewayMethod) {
        api.registerGatewayMethod("notice.getRecentMessages", ({ respond }) => {
            respond(true, [...recentMessages]);
        });
    }
}
