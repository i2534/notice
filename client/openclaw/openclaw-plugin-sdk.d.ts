/**
 * Minimal type declarations for openclaw/plugin-sdk when the host is not installed.
 * Aligned with openclaw@2026.3.13 (plugin-sdk). Install openclaw (peerDependency) for full types.
 */
declare module "openclaw/plugin-sdk" {
    export type ChannelConfigUiHint = {
        label?: string;
        help?: string;
        tags?: string[];
        advanced?: boolean;
        sensitive?: boolean;
        placeholder?: string;
        itemTemplate?: unknown;
    };

    export type ChannelConfigSchema = {
        schema: Record<string, unknown>;
        uiHints?: Record<string, ChannelConfigUiHint>;
    };

    export type PluginLogger = {
        debug?: (message: string, ...args: unknown[]) => void;
        info: (message: string, ...args: unknown[]) => void;
        warn: (message: string, ...args: unknown[]) => void;
        error: (message: string, ...args: unknown[]) => void;
    };

    export type PluginRuntime = {
        system?: {
            enqueueSystemEvent?: (text: string, opts: { sessionKey: string; contextKey?: string | null }) => void;
        };
        stt?: {
            transcribeAudioFile?: (params: { filePath: string; cfg: Record<string, unknown>; agentDir?: string; mime?: string }) => Promise<{ text: string | undefined }>;
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
                    ctx: T & { CommandAuthorized?: boolean },
                    opts?: unknown
                ) => T & { CommandAuthorized: boolean };
                dispatchReplyWithBufferedBlockDispatcher?: (params: {
                    ctx: Record<string, unknown>;
                    cfg: Record<string, unknown>;
                    dispatcherOptions: {
                        deliver: (
                            payload: { text?: string; mediaUrl?: string; mediaUrls?: string[] },
                            info?: { kind: string }
                        ) => Promise<void>;
                    };
                }) => Promise<{ queuedFinal: boolean }>;
            };
        };
    };

    export type OpenClawPluginService = {
        id: string;
        start: (ctx: { config: Record<string, unknown>; stateDir?: string; logger?: PluginLogger }) => void | Promise<void>;
        stop?: (ctx: { config: Record<string, unknown>; stateDir?: string; logger?: PluginLogger }) => void | Promise<void>;
    };

    export type OpenClawPluginApi = {
        config: Record<string, unknown>;
        logger?: PluginLogger;
        runtime?: PluginRuntime;
        registerChannel: (opts: { plugin: unknown } | unknown) => void;
        registerService?: (service: OpenClawPluginService) => void;
        registerGatewayMethod?: (
            method: string,
            handler: (arg: { respond: (ok: boolean, data?: unknown) => void }) => void
        ) => void;
    };
}
