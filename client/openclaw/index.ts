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
import { existsSync } from "fs";
import path from "path";
import { spawn } from "node:child_process";
import type {
    ChannelConfigSchema,
    OpenClawPluginApi,
    PluginLogger,
    PluginRuntime,
} from "openclaw/plugin-sdk";
import mqtt from "mqtt";
import { gzipSync, gunzipSync } from "node:zlib";

// ---------------------------------------------------------------------------
// 常量
// ---------------------------------------------------------------------------

const CHANNEL_ID = "notice";
const DEFAULT_TOPIC = "notice/openclaw";
const OUTBOUND_CLIENT_ID = "openclaw";
const MAX_RECENT_MESSAGES = 50;
const PENDING_ASR_TIMEOUT_MS = 5 * 60 * 1000; // 5 min
const MAX_VOICE_QUEUE_PER_KEY = 20;

/** key 分隔符（topic/client 中不会出现），避免 topic 含 \n 时反解错误 */
const PENDING_KEY_SEP = "\x00";

/** 待确认语音转写：key = topic + SEP + client，同一 key 同时只有一条在等待确认 */
const pendingAsrConfirm = new Map<string, { draftText: string; at: number }>();

/** 同一 (topic, client) 下连续多条语音 URL 排队，当前条确认或超时后处理下一条 */
const voiceQueueByKey = new Map<string, string[]>();

function pendingKey(topic: string, client: string): string {
    return topic + PENDING_KEY_SEP + (client || "unknown");
}

/** 清理超时的待确认项，返回被清理的 key 列表，便于后续处理该 key 的队列下一项 */
function prunePendingAsrConfirm(): string[] {
    const now = Date.now();
    const pruned: string[] = [];
    for (const [k, v] of pendingAsrConfirm.entries()) {
        if (now - v.at > PENDING_ASR_TIMEOUT_MS) {
            pendingAsrConfirm.delete(k);
            pruned.push(k);
        }
    }
    return pruned;
}

// ---------------------------------------------------------------------------
// 配置与账号
// ---------------------------------------------------------------------------

function getChannelConfig(cfg: Record<string, unknown>): Record<string, unknown> | undefined {
    return (cfg?.channels as Record<string, unknown>)?.[CHANNEL_ID] as Record<string, unknown> | undefined;
}

/** 从主配置解析显示名（ui.assistant.name → agents.list → agents.defaults → default），用于 MQTT 消息 title */
function resolveAgentDisplayName(cfg: Record<string, unknown>): string {
    const topKeys = cfg ? Object.keys(cfg).join(",") : "";
    const agents = cfg?.agents as Record<string, unknown> | undefined;
    const agentsKeys = agents && typeof agents === "object" ? Object.keys(agents).join(",") : "no-agents";
    const list = agents?.list as Array<Record<string, unknown>> | undefined;
    const listLen = Array.isArray(list) ? list.length : "none";
    const listType =
        list === undefined ? "undefined" : Array.isArray(list) ? "array(" + list.length + ")" : typeof list;

    let result: string;
    let source: string;

    const uiAssistant = cfg?.ui as Record<string, unknown> | undefined;
    const assistant = uiAssistant?.assistant as Record<string, unknown> | undefined;
    const uiName = assistant && typeof assistant.name === "string" ? assistant.name.trim() : "";
    if (uiName) {
        result = uiName;
        source = "ui.assistant.name";
    } else if (!Array.isArray(list) || list.length === 0) {
        const defaults = agents?.defaults as Record<string, unknown> | undefined;
        const defIdentity = defaults?.identity as Record<string, unknown> | undefined;
        const defIdentityName =
            defIdentity && typeof defIdentity.name === "string" ? defIdentity.name.trim() : "";
        const defName = typeof defaults?.name === "string" ? (defaults.name as string).trim() : "";
        if (defIdentityName) {
            result = defIdentityName;
            source = "agents.defaults.identity.name";
        } else if (defName) {
            result = defName;
            source = "agents.defaults.name";
        } else {
            result = "Openclaw";
            source = "default(no agents.list)";
        }
    } else {
        let defaultAgent: Record<string, unknown> | undefined;
        for (const item of list) {
            if (item && item.default === true) {
                defaultAgent = item;
                break;
            }
        }
        if (!defaultAgent) defaultAgent = list[0];
        if (!defaultAgent) {
            result = "Openclaw";
            source = "default(empty list)";
        } else {
            const identity = defaultAgent.identity as Record<string, unknown> | undefined;
            const identityName = identity && typeof identity.name === "string" ? identity.name.trim() : "";
            if (identityName) {
                result = identityName;
                source = "agents.list[].identity.name";
            } else {
                const name = typeof defaultAgent.name === "string" ? defaultAgent.name.trim() : "";
                if (name) {
                    result = name;
                    source = "agents.list[].name";
                } else {
                    const id = typeof defaultAgent.id === "string" ? defaultAgent.id.trim() : "";
                    if (id) {
                        result = id;
                        source = "agents.list[].id";
                    } else {
                        result = "Openclaw";
                        source = "default";
                    }
                }
            }
        }
    }

    pluginLogger.info(
        "[notice] resolveAgentDisplayName result=" +
            result +
            " source=" +
            source +
            " cfgKeys=" +
            (topKeys || "(empty)") +
            " agentsKeys=" +
            agentsKeys +
            " agentsListLen=" +
            String(listLen) +
            " listType=" +
            listType
    );
    return result;
}

/** 未配置 maxContentLength 时：0=整段发送、不按长度分块（默认不截断） */
const DEFAULT_MAX_CONTENT_LENGTH = 0;

/** MQTT JSON：content 为 gzip(UTF-8)+standard base64 时的 content_encoding */
const CONTENT_ENCODING_GZIP_B64 = "gzip+base64";

/** Unicode 标量值数量（与 Go utf8.RuneCount 对齐的常见文本场景） */
function countRunes(s: string): number {
    return [...s].length;
}

/** 从通道配置读取单条消息最大内容长度：0=不限制（不分块）；正整数为每块最大 rune 数；未配置或无效时默认 0（不截断） */
function resolveMaxContentLength(cfg: Record<string, unknown>): number {
    const ch = getChannelConfig(cfg);
    const v = ch?.maxContentLength;
    if (typeof v === "number" && Number.isInteger(v)) {
        if (v === 0) return 0;
        if (v > 0) return v;
    }
    return DEFAULT_MAX_CONTENT_LENGTH;
}

/** 仅当正文 rune 数 >= 此值时才尝试 gzip+base64；0 表示不压缩 */
function resolveCompressMinRunes(cfg: Record<string, unknown>): number {
    const ch = getChannelConfig(cfg);
    const v = ch?.compressMinRunes;
    if (typeof v === "number" && Number.isInteger(v) && v >= 0) return v;
    return 0;
}

/** 是否允许将「/」开头消息按命令授权处理（默认 true） */
function resolveAllowSlashCommands(cfg: Record<string, unknown>): boolean {
    const ch = getChannelConfig(cfg);
    if (ch?.allowSlashCommands === false) return false;
    return true;
}

function shouldAuthorizeSlashCommand(cfg: Record<string, unknown>, messageTrimmed: string): boolean {
    const t = messageTrimmed.trim();
    return resolveAllowSlashCommands(cfg) && t.startsWith("/") && t.length > 0;
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
let pluginLogger: PluginLogger = {
    info: (msg, ...args) => console.log("[notice]", msg, ...args),
    warn: (msg, ...args) => console.warn("[notice]", msg, ...args),
    error: (msg, ...args) => console.error("[notice]", msg, ...args),
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

/** 构建 MQTT 消息 JSON；cfg 省略时不压缩（用于 ASR 等控制类短 JSON） */
function buildNoticeMqttPayload(text: string, title: string, cfg?: Record<string, unknown>): string {
    const ts = Date.now();
    if (!cfg) {
        return JSON.stringify({
            title,
            content: text,
            client: OUTBOUND_CLIENT_ID,
            timestamp: ts,
        });
    }
    const minRunes = resolveCompressMinRunes(cfg);
    const utf8 = Buffer.from(text, "utf8");
    const plainLen = utf8.length;
    let content = text;
    let content_encoding: string | undefined;
    if (minRunes > 0 && plainLen > 0 && countRunes(text) >= minRunes) {
        const gz = gzipSync(utf8);
        const b64 = Buffer.from(gz).toString("base64");
        const encodedLen = b64.length;
        if (encodedLen < plainLen) {
            content = b64;
            content_encoding = CONTENT_ENCODING_GZIP_B64;
        }
    }
    const obj: Record<string, unknown> = {
        title,
        content,
        client: OUTBOUND_CLIENT_ID,
        timestamp: ts,
    };
    if (content_encoding) obj.content_encoding = content_encoding;
    return JSON.stringify(obj);
}

/** 通过 MQTT 发布一条消息（不经过 webhook）。传入 cfg 时按 compressMinRunes 与体积判据可选 gzip+base64。 */
function sendViaMqtt(
    publishTopic: string,
    text: string,
    title?: string,
    cfg?: Record<string, unknown>
): Promise<{ ok: boolean; error?: string }> {
    const c = mqttClient;
    if (!c?.connected) {
        pluginLogger.warn("[notice] Send skipped: MQTT not connected");
        return Promise.resolve({ ok: false, error: "MQTT not connected" });
    }
    const t = title ?? "Openclaw";
    const payload = buildNoticeMqttPayload(text, t, cfg);
    return new Promise((resolve) => {
        c.publish(publishTopic, payload, { qos: 1 }, (err) => {
            if (err) {
                pluginLogger.warn("[notice] Send failed topic=" + publishTopic + " error=" + String(err));
                resolve({ ok: false, error: String(err) });
            } else {
                const contentPreview = text.slice(0, 200).replace(/\s+/g, " ").trim();
                pluginLogger.info("[notice] Message sent topic=" + publishTopic + " content=" + contentPreview);
                resolve({ ok: true });
            }
        });
    });
}

/** Returns true if the string looks like a local path (not a URL). */
function isLocalPath(s: string): boolean {
    const t = s.trim();
    if (t.length === 0) return false;
    // Absolute HTTP(S) URL
    if (/^https?:\/\//i.test(t)) return false;
    // Protocol-relative URL (//host/path or //host?query) — do not treat as local file
    if (t.startsWith("//") && (t.includes("?") || /^\/\/[^/]*\.[^/]*(\/|$)/.test(t))) return false;
    return true;
}

const IMAGE_EXT_REGEX = /\/[^\s:]+\.(?:png|jpe?g|gif|webp)/gi;

/** 匹配 Notice 多媒体签名 URL（/api/media?n=...&e=...&s=...） */
const MEDIA_URL_REGEX = /https?:\/\/[^\s"'<>]+\/api\/media\?[^\s"'<>]+/gi;

/** 音频扩展名（与 server 的 media 允许扩展名及 web isAudioUrl 一致） */
const AUDIO_EXT = /\.(m4a|mp3|webm|ogg|wav|aac|opus|weba)$/i;

/** 从 /api/media URL 的 n= 参数或 path 中解析音频扩展名（含点，如 .m4a），无法推断时返回 .m4a */
function getAudioExtensionFromUrl(url: string): string {
    if (!url || typeof url !== "string") return ".m4a";
    const decoded = url.replace(/&amp;/gi, "&");
    const pathPart = decoded.split("#")[0].split("?")[0];
    const pathMatch = pathPart.match(AUDIO_EXT);
    if (pathMatch) return pathMatch[0].toLowerCase();
    const qs = decoded.split("#")[0];
    const qi = qs.indexOf("?");
    if (qi >= 0) {
        for (const part of qs.slice(qi + 1).split("&")) {
            const eq = part.indexOf("=");
            if (eq > 0 && part.slice(0, eq).toLowerCase() === "n") {
                try {
                    const val = decodeURIComponent(part.slice(eq + 1));
                    const m = val.match(AUDIO_EXT);
                    if (m) return m[0].toLowerCase();
                } catch {
                    /* ignore */
                }
                break;
            }
        }
    }
    return ".m4a";
}

/** 判断 /api/media URL 是否为音频（根据 n= 参数或 path 的扩展名） */
function isAudioMediaUrl(url: string): boolean {
    if (!url || typeof url !== "string") return false;
    const decoded = url.replace(/&amp;/gi, "&");
    const pathPart = decoded.split("#")[0].split("?")[0];
    if (AUDIO_EXT.test(pathPart)) return true;
    const qs = decoded.split("#")[0];
    const qi = qs.indexOf("?");
    if (qi >= 0) {
        for (const part of qs.slice(qi + 1).split("&")) {
            const eq = part.indexOf("=");
            if (eq > 0 && part.slice(0, eq).toLowerCase() === "n") {
                try {
                    const val = decodeURIComponent(part.slice(eq + 1));
                    if (AUDIO_EXT.test(val)) return true;
                } catch {
                    /* ignore */
                }
                break;
            }
        }
    }
    return false;
}

function extractMediaUrls(text: string): string[] {
    if (!text || typeof text !== "string") return [];
    const out: string[] = [];
    let m: RegExpExecArray | null;
    MEDIA_URL_REGEX.lastIndex = 0;
    while ((m = MEDIA_URL_REGEX.exec(text)) !== null) {
        const u = m[0];
        if (!out.includes(u)) out.push(u);
    }
    return out;
}

/** 下载 URL 到临时文件，返回临时文件路径；失败返回 null。带扩展名便于 ASR 识别格式。 */
async function downloadMediaToTemp(url: string): Promise<string | null> {
    const os = await import("os");
    const tmpDir = os.tmpdir();
    const ext = getAudioExtensionFromUrl(url);
    const name = "notice_media_" + Date.now() + "_" + Math.random().toString(36).slice(2, 10) + ext;
    const tmpPath = path.join(tmpDir, name);
    try {
        const res = await fetch(url);
        if (!res.ok) {
            pluginLogger.warn("[notice] Download media failed url=" + url.slice(0, 80) + " status=" + res.status);
            return null;
        }
        const buf = Buffer.from(await res.arrayBuffer());
        await fs.writeFile(tmpPath, buf);
        return tmpPath;
    } catch (e) {
        pluginLogger.warn("[notice] Download media error " + String(e));
        return null;
    }
}

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
    log: PluginLogger
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

function chunkTextForNotice(text: string, maxLen: number = DEFAULT_MAX_CONTENT_LENGTH): string[] {
    const t = text.trim();
    if (!t) return [];
    if (maxLen <= 0) return [t];
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
    log: Pick<PluginLogger, "warn">,
    cfg: Record<string, unknown>
): Promise<boolean> {
    const topic = topicForPublish(rawTopic);
    const title = resolveAgentDisplayName(cfg);
    const maxLen = resolveMaxContentLength(cfg);
    const chunks = chunkTextForNotice(text, maxLen);
    for (const chunk of chunks) {
        const ok = await sendViaMqtt(topic, chunk, title, cfg);
        if (!ok.ok) {
            log.warn("[notice] Reply send failed " + (ok.error ?? ""));
            return false;
        }
    }
    pluginLogger.info("[notice] Reply sent topic=" + topic + " chunks=" + chunks.length);
    return true;
}

/** 若 STT 在此时长（毫秒）内返回且结果为空，则尝试用 models 中的 CLI 直接调用 */
const FAST_EMPTY_THRESHOLD_MS = 2000;

interface CliModel {
    command: string;
    args: string[];
    timeoutSeconds: number;
}

function getFirstCliModel(cfg: Record<string, unknown>): CliModel | null {
    const tools = cfg?.tools as Record<string, unknown> | undefined;
    const media = tools?.media as Record<string, unknown> | undefined;
    const audio = media?.audio as Record<string, unknown> | undefined;
    if (!audio || audio.enabled === false) return null;
    const models = audio.models as Array<Record<string, unknown>> | undefined;
    if (!Array.isArray(models)) return null;
    for (const m of models) {
        if (m?.type === "cli" && typeof m?.command === "string") {
            const args = Array.isArray(m.args)
                ? (m.args as string[]).map((a) => String(a))
                : [];
            const timeoutSeconds =
                typeof m.timeoutSeconds === "number" && m.timeoutSeconds > 0
                    ? m.timeoutSeconds
                    : 60;
            return { command: m.command, args, timeoutSeconds };
        }
    }
    return null;
}

/** 使用配置中的 CLI 直接转写；占位符 {{MediaPath}} 替换为 filePath；结果从 stdout 读取。command 需为绝对路径。 */
function runCliTranscribe(filePath: string, model: CliModel): Promise<string> {
    const timeoutMs = model.timeoutSeconds * 1000;
    const args = model.args.map((a) =>
        a === "{{MediaPath}}" || a === "{filePath}" ? filePath : a
    );
    return new Promise((resolve) => {
        const child = spawn(model.command, args, {
            stdio: ["ignore", "pipe", "pipe"],
        });
        let stdout = "";
        let stderr = "";
        child.stdout?.setEncoding("utf8");
        child.stdout?.on("data", (chunk) => {
            stdout += chunk;
        });
        child.stderr?.setEncoding("utf8");
        child.stderr?.on("data", (chunk) => {
            stderr += chunk;
        });
        const timer = setTimeout(() => {
            child.kill("SIGTERM");
            try {
                child.kill("SIGKILL");
            } catch {
                /* ignore */
            }
            resolve(stdout.trim());
        }, timeoutMs);
        child.once("close", () => {
            clearTimeout(timer);
            if (stderr) {
                pluginLogger.info("[notice] runCliTranscribe stderr: " + stderr.slice(0, 200));
            }
            resolve(stdout.trim());
        });
        child.once("error", (err) => {
            clearTimeout(timer);
            pluginLogger.warn("[notice] runCliTranscribe error " + String(err));
            resolve("");
        });
    });
}

/** 发送「请确认语音转写」到 client（content 为 JSON：type asr_confirm_request, text） */
async function sendAsrConfirmRequest(publishTopic: string, draftText: string): Promise<boolean> {
    const content = JSON.stringify({ type: "asr_confirm_request", text: draftText });
    const ok = await sendViaMqtt(publishTopic, content, "请确认语音转写");
    return ok.ok;
}

/** 处理单条语音 URL：下载 → 使用 Openclaw runtime.stt.transcribeAudioFile 转写 → 发送请确认成功后才写入 pending */
async function processOneVoiceUrl(
    api: OpenClawPluginApi,
    cfg: Record<string, unknown>,
    topicStr: string,
    clientId: string,
    audioUrl: string
): Promise<void> {
    const key = pendingKey(topicStr, clientId);
    const tmpPath = await downloadMediaToTemp(audioUrl);

    let draftText = "";
    if (tmpPath && existsSync(tmpPath)) {
        try {
            const transcribe = api.runtime?.stt?.transcribeAudioFile;
            if (transcribe) {
                pluginLogger.info("[notice] processOneVoiceUrl transcribing path=" + tmpPath);
                const t0 = Date.now();
                const result = await transcribe({ filePath: tmpPath, cfg });
                const raw = result?.text?.trim() ?? "";
                const elapsed = Date.now() - t0;
                if (raw) {
                    draftText = raw;
                    pluginLogger.info("[notice] processOneVoiceUrl transcribe ok len=" + raw.length + " preview=" + (raw.slice(0, 40) + (raw.length > 40 ? "..." : "")));
                } else if (elapsed < FAST_EMPTY_THRESHOLD_MS) {
                    const cliModel = getFirstCliModel(cfg);
                    if (cliModel) {
                        pluginLogger.info("[notice] processOneVoiceUrl fallback: running CLI (empty in " + elapsed + "ms)");
                        const cliText = await runCliTranscribe(tmpPath, cliModel);
                        draftText = cliText || "[语音识别失败]";
                        if (cliText) {
                            pluginLogger.info("[notice] processOneVoiceUrl fallback ok len=" + cliText.length);
                        }
                    } else {
                        draftText = "[语音识别失败]";
                        pluginLogger.warn("[notice] processOneVoiceUrl transcribe returned empty text (no CLI fallback in config)");
                    }
                } else {
                    draftText = "[语音识别失败]";
                    pluginLogger.warn("[notice] processOneVoiceUrl transcribe returned empty text");
                }
            } else {
                pluginLogger.warn("[notice] processOneVoiceUrl STT not configured (no runtime.stt.transcribeAudioFile)");
                draftText =
                    "[语音消息，未配置转写] 请在 Openclaw 主配置（如 ~/.openclaw/openclaw.json）中配置 tools.media.audio 以启用语音转写。详见：https://openclaw.dev/docs#tools-media";
            }
        } catch (e) {
            pluginLogger.warn("[notice] processOneVoiceUrl transcribeAudioFile error " + String(e));
            draftText = "[语音识别失败]";
        } finally {
            try {
                await fs.unlink(tmpPath);
            } catch {
                /* ignore */
            }
        }
    } else {
        draftText = "[语音下载失败]";
    }

    const pubTopic = topicForPublish(topicStr);
    pluginLogger.info("[notice] processOneVoiceUrl sending asr_confirm_request draftLen=" + draftText.length + " topic=" + pubTopic);
    const sendOk = await sendAsrConfirmRequest(pubTopic, draftText);
    if (sendOk) {
        pendingAsrConfirm.set(key, { draftText, at: Date.now() });
        pluginLogger.info("[notice] processOneVoiceUrl sent ok pending key=" + key);
    } else {
        pluginLogger.warn("[notice] processOneVoiceUrl send asr_confirm_request failed, skipping pending for key=" + key);
        await processNextInQueue(api, key);
    }
}

/** 从 key 对应队列取出一条 URL 并处理；无队列或队列空则不再操作 */
async function processNextInQueue(api: OpenClawPluginApi, key: string): Promise<void> {
    const sepIndex = key.indexOf(PENDING_KEY_SEP);
    const topicStr = sepIndex >= 0 ? key.slice(0, sepIndex) : key;
    const clientId = sepIndex >= 0 ? key.slice(sepIndex + 1) || "unknown" : "unknown";
    const queue = voiceQueueByKey.get(key);
    if (!queue || queue.length === 0) {
        voiceQueueByKey.delete(key);
        return;
    }
    const url = queue.shift()!;
    if (queue.length === 0) voiceQueueByKey.delete(key);
    const cfg = api.config ?? {};
    await processOneVoiceUrl(api, cfg, topicStr, clientId, url);
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
            maxContentLength: { type: "number", default: 0 },
            compressMinRunes: { type: "number", default: 0 },
            allowSlashCommands: { type: "boolean", default: true },
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
        maxContentLength: {
            label: "单条 MQTT 正文最大长度（rune）",
            help: "分块时每块最大 rune 数；0 表示不限制、整段发送（默认）。若经 Webhook 发往 Notice 且服务端限制了 max_content_length，请设为不超过该值。",
        },
        compressMinRunes: {
            label: "压缩最小正文（rune）",
            help: "仅当正文 rune 数≥此值且 gzip+base64 比 UTF-8 更短时才对 content 编码；0 表示从不压缩。服务端不配置，仅发布端策略。",
        },
        allowSlashCommands: {
            label: "允许 / 命令",
            help: "开启时将以「/」开头的入站消息标记为 CommandAuthorized（MQTT 与 token 同权，请确认信任模型）。",
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
            const title = resolveAgentDisplayName(cfg);
            const maxLen = resolveMaxContentLength(cfg);
            const chunks = chunkTextForNotice(ctx.text, maxLen);
            for (const chunk of chunks) {
                const ok = await sendViaMqtt(publishTopic, chunk, title, cfg);
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

function decodeNoticeJsonContent(msg: { content?: string; content_encoding?: string }): string {
    if (msg.content == null) return "";
    const raw = String(msg.content);
    if (msg.content_encoding !== CONTENT_ENCODING_GZIP_B64) return raw;
    try {
        const buf = Buffer.from(raw, "base64");
        return gunzipSync(buf).toString("utf8");
    } catch (e) {
        pluginLogger.warn("[notice] gzip+base64 decode failed: " + String(e));
        return raw;
    }
}

function parseMqttMessage(
    payloadStr: string
): { messageText: string; contentPreview: string; skip: boolean } {
    let contentPreview = payloadStr.slice(0, 200);
    let messageText = payloadStr;
    try {
        const msg = JSON.parse(payloadStr) as {
            content?: string;
            title?: string;
            client?: string;
            content_encoding?: string;
        };
        if (msg.content === "__auth_check__") return { messageText: "", contentPreview: "", skip: true };
        if (msg.client === OUTBOUND_CLIENT_ID) return { messageText: "", contentPreview: "", skip: true };
        if (msg.content != null) {
            const dec = decodeNoticeJsonContent(msg);
            contentPreview = dec.slice(0, 200);
            messageText = dec;
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
    const hookPath = ((hooks?.path ?? "/hooks") as string).replace(/\/+$/, "");
    return {
        port,
        path: hookPath,
        enabled: Boolean(hooks?.enabled && hooks?.token),
        token: String(hooks?.token ?? ""),
    };
}

/**
 * Resolve media payload: upload local file paths to Notice server, then build content
 * with text + markdown image links ![](url) for all image URLs.
 */
async function resolveMediaAndBuildContent(
    cfg: Record<string, unknown>,
    payload: { text?: string; mediaUrl?: string; mediaUrls?: string[] },
    log: PluginLogger
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
    const commandAuthorized = shouldAuthorizeSlashCommand(cfg, messageTrimmed);
    const ctxPayload = reply.finalizeInboundContext({
        Body: envelope,
        RawBody: messageTrimmed,
        CommandBody: messageTrimmed,
        CommandAuthorized: commandAuthorized,
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
                await sendReplyChunked(topicStr, text, pluginLogger, cfg);
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
    const commandAuthorized = shouldAuthorizeSlashCommand(cfg, messageTrimmed);
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
                commandAuthorized,
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
        debug: hostLogger?.debug ? (msg, ...args) => hostLogger.debug!(msg, ...args) : undefined,
        info: (msg, ...args) => (hostLogger?.info ? hostLogger.info(msg, ...args) : console.log("[notice]", msg, ...args)),
        warn: (msg, ...args) => (hostLogger?.warn ? hostLogger.warn(msg, ...args) : console.warn("[notice]", msg, ...args)),
        error: (msg, ...args) => (hostLogger?.error ? hostLogger.error(msg, ...args) : console.error("[notice]", msg, ...args)),
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

                    let clientId = "";
                    try {
                        const msg = JSON.parse(payloadStr) as { client?: string };
                        if (typeof msg.client === "string") clientId = msg.client;
                    } catch {
                        /* non-JSON */
                    }

                    recentMessages.unshift({ topic: t, payload: payloadStr, at: Date.now() });
                    if (recentMessages.length > MAX_RECENT_MESSAGES) recentMessages.pop();
                    pluginLogger.info("[notice] Message received topic=" + t + " content=" + contentPreview);

                    const trimmed = messageText.trim();
                    if (!trimmed) return;

                    const prunedKeys = prunePendingAsrConfirm();
                    for (const key of prunedKeys) {
                        try {
                            await processNextInQueue(api, key);
                        } catch (e) {
                            pluginLogger.warn("[notice] processNextInQueue after prune failed " + String(e));
                        }
                    }
                    const topicStr = String(t);
                    const cfg = api.config ?? {};

                    // 1) 确认为「asr_confirm」格式且存在待确认 -> 投递 Agent 并清除待确认，再处理队列下一条
                    try {
                        const parsed = JSON.parse(trimmed) as { type?: string; text?: string };
                        if (parsed?.type === "asr_confirm" && typeof parsed.text === "string") {
                            const key = pendingKey(topicStr, clientId);
                            const pending = pendingAsrConfirm.get(key);
                            if (pending) {
                                pendingAsrConfirm.delete(key);
                                const confirmedText = (parsed.text as string).trim();
                                if (confirmedText) {
                                    await handleInboundMessage(api, topicStr, payloadStr, confirmedText, contentPreview);
                                }
                                try {
                                    await processNextInQueue(api, key);
                                } catch (e) {
                                    pluginLogger.warn("[notice] processNextInQueue after asr_confirm failed " + String(e));
                                }
                                return;
                            }
                        }
                    } catch {
                        /* not JSON or not asr_confirm */
                    }

                    // 2) 内容含语音 URL（仅 /api/media 且 n= 为音频扩展）-> 排队：有待确认则入队，否则处理队首并发请确认
                    const mediaUrls = extractMediaUrls(trimmed);
                    const audioUrls = mediaUrls.filter(isAudioMediaUrl);
                    if (audioUrls.length > 0) {
                        const key = pendingKey(topicStr, clientId);
                        if (!voiceQueueByKey.has(key)) voiceQueueByKey.set(key, []);
                        const queue = voiceQueueByKey.get(key)!;
                        for (const u of audioUrls) {
                            if (queue.length >= MAX_VOICE_QUEUE_PER_KEY) {
                                queue.shift();
                                pluginLogger.warn("[notice] Voice queue full, dropped oldest for key");
                            }
                            queue.push(u);
                        }
                        if (pendingAsrConfirm.has(key)) {
                            return;
                        }
                        const firstUrl = queue.shift()!;
                        if (queue.length === 0) voiceQueueByKey.delete(key);
                        await processOneVoiceUrl(api, cfg, topicStr, clientId, firstUrl);
                        return;
                    }

                    // 3) 普通消息
                    await handleInboundMessage(api, topicStr, payloadStr, trimmed, contentPreview);
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
