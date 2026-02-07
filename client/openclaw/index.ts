/**
 * Notice channel plugin for Openclaw.
 * - Sends messages to Notice server via webhook (POST /webhook).
 * - Receives messages by subscribing to MQTT; delivers to agent via runtime or hooks.
 *
 * Config: channels.notice
 *   serverUrl, token, brokerUrl (optional), topic (default notice/openclaw)
 */

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

function getNoticeCredentials(cfg: Record<string, unknown>): { serverUrl: string; token: string } | null {
    const ch = getChannelConfig(cfg);
    const acc = resolveAccount(cfg, "default");
    const serverUrl = ((ch?.serverUrl ?? acc?.serverUrl) as string)?.trim();
    const token = ((ch?.token ?? acc?.token) as string)?.trim();
    if (!serverUrl || !token) return null;
    return { serverUrl, token };
}

// ---------------------------------------------------------------------------
// Webhook 发送与分片
// ---------------------------------------------------------------------------

async function sendToNotice(
    serverUrl: string,
    token: string,
    text: string,
    title?: string,
    topic?: string
): Promise<{ ok: boolean; error?: string }> {
    const url = serverUrl.replace(/\/+$/, "") + "/webhook";
    const body: Record<string, unknown> = {
        content: text,
        title: title ?? "Openclaw",
        client: OUTBOUND_CLIENT_ID,
    };
    if (topic) body.topic = topic;
    try {
        const res = await fetch(url, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                Authorization: `Bearer ${token}`,
            },
            body: JSON.stringify(body),
        });
        if (!res.ok) {
            const t = await res.text();
            return { ok: false, error: `${res.status}: ${t}` };
        }
        return { ok: true };
    } catch (e) {
        return { ok: false, error: String(e) };
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
    serverUrl: string,
    token: string,
    topic: string,
    text: string,
    log: { warn?: (msg: string) => void }
): Promise<boolean> {
    const chunks = chunkTextForNotice(text);
    for (const chunk of chunks) {
        const ok = await sendToNotice(serverUrl, token, chunk, undefined, topic);
        if (!ok.ok) {
            log.warn?.("[notice] Reply send failed: " + (ok.error ?? ""));
            return false;
        }
    }
    return true;
}

// ---------------------------------------------------------------------------
// 类型：插件 API 与 runtime（与 agentspace 一致）
// ---------------------------------------------------------------------------

interface ApiRuntime {
    system?: {
        enqueueSystemEvent?: (text: string, opts: { sessionKey: string; contextKey?: string | null }) => void;
    };
    channel?: {
        session?: {
            resolveStorePath?: (store?: string, opts?: { agentId?: string }) => string;
            updateLastRoute?: (params: {
                storePath: string;
                sessionKey: string;
                channel?: string;
                to?: string;
            }) => Promise<unknown>;
        };
        routing?: {
            resolveAgentRoute?: (input: {
                cfg: Record<string, unknown>;
                channel: string;
                accountId?: string | null;
                peer?: { kind: string; id: string } | null;
            }) => { sessionKey: string; accountId: string };
        };
        reply?: {
            formatAgentEnvelope?: (params: {
                channel: string;
                from?: string;
                timestamp?: number | Date;
                body: string;
                envelope?: unknown;
            }) => string;
            resolveEnvelopeFormatOptions?: (cfg?: Record<string, unknown>) => unknown;
            finalizeInboundContext?: <T extends Record<string, unknown>>(
                ctx: T,
                opts?: unknown
            ) => T & { CommandAuthorized: boolean };
            dispatchReplyWithBufferedBlockDispatcher?: (params: {
                ctx: Record<string, unknown>;
                cfg: Record<string, unknown>;
                dispatcherOptions: {
                    deliver: (
                        payload: { text?: string; mediaUrl?: string; mediaUrls?: string[] },
                        info: { kind: string }
                    ) => Promise<void>;
                };
            }) => Promise<{ queuedFinal: boolean }>;
        };
    };
}

interface RegisterApi {
    registerChannel: (opts: { plugin: typeof noticeChannel }) => void;
    registerService?: (opts: {
        id: string;
        start: () => void | Promise<void>;
        stop?: () => void | Promise<void>;
    }) => void;
    registerGatewayMethod?: (
        name: string,
        handler: (arg: { respond: (ok: boolean, data?: unknown) => void }) => void
    ) => void;
    config?: Record<string, unknown>;
    logger?: { info: (msg: string, ...args: unknown[]) => void; warn: (msg: string, ...args: unknown[]) => void };
    runtime?: ApiRuntime;
}

// ---------------------------------------------------------------------------
// Channel 定义
// ---------------------------------------------------------------------------

const noticeChannelConfigSchema = {
    schema: {
        type: "object" as const,
        additionalProperties: false,
        properties: {
            serverUrl: { type: "string" },
            token: { type: "string" },
            brokerUrl: { type: "string" },
            topic: { type: "string", default: DEFAULT_TOPIC },
        },
    },
    uiHints: {
        serverUrl: { label: "Server URL", placeholder: "https://notice.example.com" },
        token: { label: "Token", sensitive: true },
        brokerUrl: { label: "MQTT Broker URL", placeholder: "wss://... or tcp://..." },
        topic: { label: "Topic", placeholder: DEFAULT_TOPIC },
    },
};

const noticeChannel = {
    id: CHANNEL_ID,
    meta: {
        id: CHANNEL_ID,
        label: "Notice",
        selectionLabel: "Notice (MQTT push)",
        docsPath: "/channels/notice",
        blurb: "Send and receive messages through Notice server.",
        aliases: ["notice"],
    },
    capabilities: { chatTypes: ["direct"] as const },
    configSchema: noticeChannelConfigSchema,
    config: { listAccountIds, resolveAccount },
    gateway: { start: async () => {}, stop: async () => {} },
    onboarding: {
        channel: CHANNEL_ID,
        getStatus: async ({ cfg }: { cfg: Record<string, unknown> }) => {
            const ch = getChannelConfig(cfg);
            const serverUrl = String(ch?.serverUrl ?? "").trim();
            const token = String(ch?.token ?? "").trim();
            const configured = Boolean(serverUrl && token);
            return {
                channel: CHANNEL_ID,
                configured,
                statusLines: configured
                    ? ["Notice: configured (server, token, broker URL, topic)"]
                    : ["Notice: needs server URL and token"],
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
            const serverUrl = (
                await prompter.text({
                    message: "Notice server URL",
                    placeholder: "https://notice.example.com",
                    initialValue: String(ch.serverUrl ?? "").trim(),
                })
            ).trim();
            const token = (
                await prompter.text({
                    message: "Auth token",
                    placeholder: "your token",
                    initialValue: String(ch.token ?? "").trim(),
                })
            ).trim();
            const brokerUrl = (
                await prompter.text({
                    message: "MQTT broker URL (optional, for receiving)",
                    placeholder: "wss://... or tcp://...",
                    initialValue: String(ch.brokerUrl ?? "").trim(),
                })
            ).trim();
            const topic = (
                await prompter.text({
                    message: "MQTT topic",
                    placeholder: DEFAULT_TOPIC,
                    initialValue: String(ch.topic ?? DEFAULT_TOPIC).trim(),
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
                            serverUrl: serverUrl || (ch.serverUrl ?? ""),
                            token: token || (ch.token ?? ""),
                            brokerUrl: brokerUrl || (ch.brokerUrl ?? ""),
                            topic: topic || (ch.topic ?? DEFAULT_TOPIC),
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
            const creds = getNoticeCredentials(cfg);
            if (!creds) return { ok: false, error: "Missing serverUrl or token in channels.notice" };
            const acc = resolveAccount(cfg, ctx.accountId);
            const topic =
                (typeof ctx.to === "string" && ctx.to.trim() ? ctx.to.trim() : null) ??
                (acc.topic as string) ??
                (getChannelConfig(cfg)?.topic as string);
            const chunks = chunkTextForNotice(ctx.text);
            for (const chunk of chunks) {
                const ok = await sendToNotice(creds.serverUrl, creds.token, chunk, undefined, topic);
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

async function deliverInboundViaDispatch(
    api: RegisterApi,
    cfg: Record<string, unknown>,
    topicStr: string,
    messageTrimmed: string
): Promise<boolean> {
    const runtime = api.runtime as ApiRuntime | undefined;
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
        To: "notice:" + topicStr,
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
    const creds = getNoticeCredentials(cfg);
    if (!creds) {
        api.logger?.warn?.("[notice] Missing serverUrl or token, cannot deliver reply");
        return true; // consumed
    }
    api.logger?.info?.("[notice] Dispatching to agent sessionKey=" + route.sessionKey);
    await reply.dispatchReplyWithBufferedBlockDispatcher({
        ctx: ctxPayload,
        cfg,
        dispatcherOptions: {
            deliver: async (payload) => {
                const text = buildReplyText(payload);
                if (!text.trim()) return;
                await sendReplyChunked(
                    creds.serverUrl,
                    creds.token,
                    topicStr,
                    text,
                    api.logger ?? {}
                );
            },
        },
    });
    return true;
}

async function deliverInboundViaEnqueueAndWake(
    api: RegisterApi,
    cfg: Record<string, unknown>,
    topicStr: string,
    messageTrimmed: string,
    safePreview: string
): Promise<boolean> {
    const runtime = api.runtime as ApiRuntime | undefined;
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
    api.logger?.info?.("[notice] Enqueued sessionKey=" + sessionKey + ", sending wake");
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
        if (!res.ok) api.logger?.warn?.("[notice] Wake failed " + res.status + " " + (await res.text()));
    } else {
        api.logger?.warn?.("[notice] Hooks disabled or no token, wake skipped");
    }
    return true;
}

async function deliverInboundViaHookAgent(
    api: RegisterApi,
    cfg: Record<string, unknown>,
    topicStr: string,
    messageTrimmed: string
): Promise<void> {
    const hooks = getHooksConfig(cfg);
    if (!hooks.enabled) {
        api.logger?.warn?.("[notice] Hooks disabled or no token, cannot deliver to agent");
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
            api.logger?.info?.("[notice] Hook agent started sessionKey=" + sessionKey);
        } else {
            api.logger?.warn?.("[notice] Hook agent failed " + res.status + " " + (await res.text()));
        }
    } catch (e) {
        api.logger?.warn?.("[notice] Hook agent error: " + String(e));
    }
}

async function handleInboundMessage(
    api: RegisterApi,
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
        api.logger?.warn?.("[notice] Inbound delivery error: " + String(e));
    }
}

// ---------------------------------------------------------------------------
// 插件注册
// ---------------------------------------------------------------------------

export default function register(api: RegisterApi): void {
    api.registerChannel({ plugin: noticeChannel });

    const cfg = api.config ?? {};
    const ch = getChannelConfig(cfg);
    const brokerUrl = (ch?.brokerUrl as string)?.trim();
    const token = ((ch?.token ?? resolveAccount(cfg, "default")?.token) as string)?.trim();
    const topic = (ch?.topic as string) ?? DEFAULT_TOPIC;

    if (brokerUrl && token && api.registerService) {
        let client: mqtt.MqttClient | null = null;
        api.registerService({
            id: "notice-mqtt",
            start: () => {
                client = mqtt.connect(brokerUrl, {
                    username: token,
                    password: token,
                    clientId: "openclaw-" + Math.random().toString(16).slice(2, 10),
                    reconnectPeriod: 5000,
                });
                client.on("connect", () => {
                    api.logger?.info?.("[notice] MQTT connected, subscribing to " + topic);
                    client!.subscribe(topic, (err) => {
                        if (err) api.logger?.warn?.("[notice] Subscribe error", err);
                    });
                });
                client.on("message", async (t, payload) => {
                    const payloadStr = typeof payload === "string" ? payload : payload?.toString?.() ?? "";
                    const { messageText, contentPreview, skip } = parseMqttMessage(payloadStr);
                    if (skip) return;

                    recentMessages.unshift({ topic: t, payload: payloadStr, at: Date.now() });
                    if (recentMessages.length > MAX_RECENT_MESSAGES) recentMessages.pop();
                    api.logger?.info?.("[notice] Message received topic=" + t + " content=" + contentPreview);

                    const trimmed = messageText.trim();
                    if (!trimmed) return;
                    await handleInboundMessage(api, String(t), payloadStr, trimmed, contentPreview);
                });
                client.on("error", (err) => api.logger?.warn?.("[notice] MQTT error", err));
            },
            stop: async () => {
                if (client) {
                    client.end();
                    client = null;
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
