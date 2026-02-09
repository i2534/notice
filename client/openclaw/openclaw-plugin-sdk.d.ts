/**
 * Minimal type declarations for openclaw/plugin-sdk when the host is not installed.
 * Install openclaw (peerDependency) for full types from the official package.
 */
declare module "openclaw/plugin-sdk" {
    export type ChannelConfigUiHint = {
        label?: string;
        help?: string;
        advanced?: boolean;
        sensitive?: boolean;
        placeholder?: string;
    };

    export type ChannelConfigSchema = {
        schema: Record<string, unknown>;
        uiHints?: Record<string, ChannelConfigUiHint>;
    };

    export type PluginRuntime = {
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
    };

    export type OpenClawPluginApi = {
        config: Record<string, unknown>;
        logger?: { info: (msg: string, ...args: unknown[]) => void; warn: (msg: string, ...args: unknown[]) => void };
        runtime?: PluginRuntime;
        registerChannel: (opts: { plugin: unknown }) => void;
        registerService?: (opts: {
            id: string;
            start: () => void | Promise<void>;
            stop?: () => void | Promise<void>;
        }) => void;
        registerGatewayMethod?: (
            name: string,
            handler: (arg: { respond: (ok: boolean, data?: unknown) => void }) => void
        ) => void;
    };
}
