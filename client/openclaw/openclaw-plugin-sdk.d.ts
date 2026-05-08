/**
 * Minimal type declarations for openclaw/plugin-sdk when the host is not installed.
 * Aligned with openclaw@2026.4.29 (plugin-sdk). Install openclaw (peerDependency) for full types.
 */
declare module "openclaw/plugin-sdk" {
    // ---------------------------------------------------------------------------
    // Channel config
    // ---------------------------------------------------------------------------

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

    // ---------------------------------------------------------------------------
    // Logger (unchanged)
    // ---------------------------------------------------------------------------

    export type PluginLogger = {
        debug?: (message: string) => void;
        info: (message: string) => void;
        warn: (message: string) => void;
        error: (message: string) => void;
    };

    // ---------------------------------------------------------------------------
    // PluginRuntime (unchanged — internal runtime surface, stays opaque)
    // ---------------------------------------------------------------------------

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
                dispatchReplyWithBlockDispatcher?: (params: {
                    ctx: Record<string, unknown>;
                    cfg: Record<string, unknown>;
                    dispatcherOptions: {
                        deliver: (
                            payload: { text?: string; mediaUrl?: string; mediaUrls?: string[] },
                            info?: { kind: string }
                        ) => Promise<void>;
                    };
                }) => Promise<{ queuedFinal: boolean }>;
                /** @deprecated Use dispatchReplyWithBlockDispatcher */
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

    // ---------------------------------------------------------------------------
    // Service
    // ---------------------------------------------------------------------------

    export type OpenClawPluginServiceContext = {
        config: OpenClawConfig;
        workspaceDir?: string;
        stateDir: string;
        logger: PluginLogger;
    };

    export type OpenClawPluginService = {
        id: string;
        start: (ctx: OpenClawPluginServiceContext) => void | Promise<void>;
        stop?: (ctx: OpenClawPluginServiceContext) => void | Promise<void>;
    };

    // ---------------------------------------------------------------------------
    // Gateway method
    // ---------------------------------------------------------------------------

    export type GatewayRequestHandler = (arg: { respond: (ok: boolean, data?: unknown) => void }) => void;

    // ---------------------------------------------------------------------------
    // Plugin API (expanded to match openclaw@2026.4.29)
    // ---------------------------------------------------------------------------

    /** Lightweight alias for the full OpenClawConfig — use Record when you only need partial access. */
    export type OpenClawConfig = Record<string, unknown>;

    export type PluginRegistrationMode = "full" | "setup-only" | "setup-runtime";

    export type OpenClawPluginApi = {
        // --- Metadata ---
        id: string;
        name: string;
        version?: string;
        description?: string;
        source: string;
        rootDir?: string;
        registrationMode: PluginRegistrationMode;

        // --- Config & runtime ---
        config: OpenClawConfig;
        pluginConfig?: Record<string, unknown>;
        logger: PluginLogger;
        runtime: PluginRuntime;

        // --- Registration methods ---
        registerTool: (tool: unknown, opts?: { name?: string; names?: string[]; optional?: boolean }) => void;
        registerHook: (events: string | string[], handler: (event: Record<string, unknown>) => void | Promise<void>, opts?: Record<string, unknown>) => void;
        registerHttpRoute: (params: { path: string; handler: unknown; auth: "gateway" | "plugin"; match?: "exact" | "prefix"; replaceExisting?: boolean }) => void;
        registerCli: (registrar: (ctx: Record<string, unknown>) => void | Promise<void>, opts?: { commands?: string[] }) => void;
        registerProvider: (provider: Record<string, unknown>) => void;
        registerSpeechProvider: (provider: Record<string, unknown>) => void;
        registerMediaUnderstandingProvider: (provider: Record<string, unknown>) => void;
        registerImageGenerationProvider: (provider: Record<string, unknown>) => void;
        registerWebSearchProvider: (provider: Record<string, unknown>) => void;
        registerInteractiveHandler: (registration: Record<string, unknown>) => void;
        registerCommand: (command: {
            name: string;
            description: string;
            acceptsArgs?: boolean;
            requireAuth?: boolean;
            handler: (ctx: Record<string, unknown>) => Record<string, unknown> | Promise<Record<string, unknown>>;
        }) => void;
        registerContextEngine: (id: string, factory: unknown) => void;
        registerMemoryPromptSection: (builder: unknown) => void;

        // --- Channel ---
        registerChannel: (registration: { plugin: unknown } | unknown) => void;

        // --- Service & gateway ---
        registerService: (service: OpenClawPluginService) => void;
        registerGatewayMethod: (method: string, handler: GatewayRequestHandler) => void;

        // --- Conversation binding ---
        onConversationBindingResolved: (handler: (event: Record<string, unknown>) => void | Promise<void>) => void;

        // --- Utilities ---
        resolvePath: (input: string) => string;

        // --- Lifecycle hooks ---
        on: <K extends PluginHookName>(hookName: K, handler: PluginHookHandlerMap[K], opts?: { priority?: number }) => void;
    };

    // ---------------------------------------------------------------------------
    // Lifecycle hook names & handler map
    // ---------------------------------------------------------------------------

    export type PluginHookName =
        | "before_model_resolve"
        | "before_prompt_build"
        | "before_agent_start"
        | "llm_input"
        | "llm_output"
        | "agent_end"
        | "before_compaction"
        | "after_compaction"
        | "before_reset"
        | "inbound_claim"
        | "message_received"
        | "message_sending"
        | "message_sent"
        | "before_tool_call"
        | "after_tool_call"
        | "tool_result_persist"
        | "before_message_write"
        | "session_start"
        | "session_end"
        | "subagent_spawning"
        | "subagent_delivery_target"
        | "subagent_spawned"
        | "subagent_ended"
        | "gateway_start"
        | "gateway_stop";

    /** Each hook handler receives a Record<string, unknown> event payload. */
    export type PluginHookHandlerMap = {
        [K in PluginHookName]: (event: Record<string, unknown>) => void | Promise<void>;
    };
}
