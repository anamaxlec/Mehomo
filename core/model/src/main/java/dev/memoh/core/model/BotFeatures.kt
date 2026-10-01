package dev.memoh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Business API responses. Unknown fields remain compatible with server updates.

@Serializable
data class MemorySearchResponse(
    @SerialName("fallback_reason") val fallbackReason: String? = null,
    val relations: List<JsonElement>? = null,
    val results: List<MemoryEntry>? = null,
    @SerialName("retrieval_mode") val retrievalMode: String? = null,
)

@Serializable
data class MemoryEntry(
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("bot_id") val botId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val hash: String? = null,
    val id: String? = null,
    val memory: String? = null,
    val metadata: JsonObject? = null,
    @SerialName("run_id") val runId: String? = null,
    val score: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class MemoryStatus(
    @SerialName("can_manual_sync") val canManualSync: Boolean? = null,
    val compact: MemoryCompactCapability? = null,
    val degraded: Boolean? = null,
    @SerialName("edge_count") val edgeCount: Long? = null,
    val encoder: HealthStatus? = null,
    @SerialName("indexed_count") val indexedCount: Long? = null,
    @SerialName("markdown_file_count") val markdownFileCount: Long? = null,
    @SerialName("memory_mode") val memoryMode: String? = null,
    @SerialName("overview_path") val overviewPath: String? = null,
    val pgvector: HealthStatus? = null,
    @SerialName("provider_type") val providerType: String? = null,
    @SerialName("retry_queue_depth") val retryQueueDepth: Long? = null,
    @SerialName("source_count") val sourceCount: Long? = null,
    @SerialName("source_dir") val sourceDir: String? = null,
    @SerialName("vector_index") val vectorIndex: String? = null,
)

@Serializable
data class MemoryCompactCapability(
    val archive: Boolean? = null,
    val reason: String? = null,
    @SerialName("rebuild_index") val rebuildIndex: Boolean? = null,
    val semantic: Boolean? = null,
)

@Serializable
data class HealthStatus(
    val error: String? = null,
    val ok: Boolean? = null,
)

@Serializable
data class MemoryGraph(
    val edges: List<MemoryGraphEdge>? = null,
    val nodes: List<MemoryGraphNode>? = null,
)

@Serializable
data class MemoryGraphEdge(
    val count: Long? = null,
    val rel: String? = null,
    val rels: List<String>? = null,
    val source: String? = null,
    val target: String? = null,
    val weight: Double? = null,
)

@Serializable
data class MemoryGraphNode(
    val count: Long? = null,
    val id: String? = null,
    val label: String? = null,
    val memory: String? = null,
    @SerialName("memory_ids") val memoryIds: List<String>? = null,
    val metadata: JsonObject? = null,
    val slug: String? = null,
    val subject: String? = null,
    val topic: String? = null,
)

@Serializable
data class BotSchedule(
    @SerialName("acp_agent_id") val acpAgentId: String? = null,
    @SerialName("acp_model_id") val acpModelId: String? = null,
    @SerialName("bot_agent_id") val botAgentId: String? = null,
    @SerialName("bot_id") val botId: String? = null,
    val command: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("current_calls") val currentCalls: Long? = null,
    val description: String? = null,
    val enabled: Boolean? = null,
    val id: String? = null,
    @SerialName("max_calls") val maxCalls: Long? = null,
    @SerialName("max_run_seconds") val maxRunSeconds: Long? = null,
    @SerialName("model_id") val modelId: String? = null,
    val name: String? = null,
    val pattern: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("run_target") val runTarget: String? = null,
    @SerialName("runtime_type") val runtimeType: String? = null,
    @SerialName("target_session_id") val targetSessionId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("workdir_id") val workdirId: String? = null,
)

@Serializable
data class ScheduleList(
    val items: List<BotSchedule>? = null,
)

@Serializable
data class ScheduleLog(
    @SerialName("bot_id") val botId: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    val id: String? = null,
    @SerialName("result_text") val resultText: String? = null,
    @SerialName("schedule_id") val scheduleId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    val status: String? = null,
    val usage: JsonElement? = null,
)

@Serializable
data class ScheduleLogs(
    val items: List<ScheduleLog>? = null,
    @SerialName("total_count") val totalCount: Long? = null,
)

@Serializable
data class TokenUsageSummary(
    @SerialName("acp_agent") val acpAgent: List<DailyTokenUsage>? = null,
    @SerialName("by_model") val byModel: List<ModelTokenUsage>? = null,
    val chat: List<DailyTokenUsage>? = null,
    val discuss: List<DailyTokenUsage>? = null,
    val schedule: List<DailyTokenUsage>? = null,
)

@Serializable
data class DailyTokenUsage(
    @SerialName("cache_read_tokens") val cacheReadTokens: Long? = null,
    val day: String? = null,
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("reasoning_tokens") val reasoningTokens: Long? = null,
)

@Serializable
data class ModelTokenUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("model_slug") val modelSlug: String? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("provider_name") val providerName: String? = null,
)

@Serializable
data class TokenUsageRecord(
    @SerialName("cache_read_tokens") val cacheReadTokens: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val harness: String? = null,
    val id: String? = null,
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("model_slug") val modelSlug: String? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("provider_name") val providerName: String? = null,
    @SerialName("reasoning_tokens") val reasoningTokens: Long? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("session_type") val sessionType: String? = null,
)

@Serializable
data class TokenUsageRecords(
    val items: List<TokenUsageRecord>? = null,
    val total: Long? = null,
)

@Serializable
data class ContainerMetrics(
    val backend: String? = null,
    val metrics: ContainerMetricsPayloadResponse? = null,
    @SerialName("resource_limits") val resourceLimits: GetContainerResourceLimitsResponse? = null,
    @SerialName("sampled_at") val sampledAt: String? = null,
    val status: ContainerMetricsStatusResponse? = null,
    val supported: Boolean? = null,
    @SerialName("unsupported_reason") val unsupportedReason: String? = null,
)

@Serializable
data class ContainerMetricsPayloadResponse(
    val cpu: ContainerCPUMetricsResponse? = null,
    val memory: ContainerMemoryMetricsResponse? = null,
    val storage: ContainerStorageMetricsResponse? = null,
)

@Serializable
data class ContainerCPUMetricsResponse(
    @SerialName("kernel_nanoseconds") val kernelNanoseconds: Long? = null,
    @SerialName("usage_nanocores") val usageNanocores: Long? = null,
    @SerialName("usage_nanoseconds") val usageNanoseconds: Long? = null,
    @SerialName("usage_percent") val usagePercent: Double? = null,
    @SerialName("user_nanoseconds") val userNanoseconds: Long? = null,
)

@Serializable
data class ContainerMemoryMetricsResponse(
    @SerialName("limit_bytes") val limitBytes: Long? = null,
    @SerialName("usage_bytes") val usageBytes: Long? = null,
    @SerialName("usage_percent") val usagePercent: Double? = null,
)

@Serializable
data class ContainerStorageMetricsResponse(
    val path: String? = null,
    @SerialName("used_bytes") val usedBytes: Long? = null,
)

@Serializable
data class GetContainerResourceLimitsResponse(
    val applied: ContainerResourceLimitValuesResponse? = null,
    val backend: String? = null,
    val capabilities: ContainerResourceLimitCapabilitiesResponse? = null,
    val desired: ContainerResourceLimitValuesResponse? = null,
    val observed: ContainerResourceLimitObservedResponse? = null,
    @SerialName("requires_recreate") val requiresRecreate: Boolean? = null,
    @SerialName("runtime_backend") val runtimeBackend: String? = null,
    val status: String? = null,
    @SerialName("workspace_backend") val workspaceBackend: String? = null,
)

@Serializable
data class ContainerResourceLimitValuesResponse(
    @SerialName("cpu_millicores") val cpuMillicores: Long? = null,
    @SerialName("memory_bytes") val memoryBytes: Long? = null,
    @SerialName("storage_bytes") val storageBytes: Long? = null,
)

@Serializable
data class ContainerResourceLimitCapabilitiesResponse(
    val cpu: ContainerResourceLimitCapabilityResponse? = null,
    val memory: ContainerResourceLimitCapabilityResponse? = null,
    val storage: ContainerResourceLimitCapabilityResponse? = null,
)

@Serializable
data class ContainerResourceLimitCapabilityResponse(
    @SerialName("hard_limit_supported") val hardLimitSupported: Boolean? = null,
    @SerialName("soft_limit_supported") val softLimitSupported: Boolean? = null,
)

@Serializable
data class ContainerResourceLimitObservedResponse(
    @SerialName("cpu_usage_percent") val cpuUsagePercent: Double? = null,
    @SerialName("memory_limit_bytes") val memoryLimitBytes: Long? = null,
    @SerialName("memory_usage_bytes") val memoryUsageBytes: Long? = null,
    @SerialName("storage_over_soft_limit") val storageOverSoftLimit: Boolean? = null,
    @SerialName("storage_used_bytes") val storageUsedBytes: Long? = null,
)

@Serializable
data class ContainerMetricsStatusResponse(
    val exists: Boolean? = null,
    @SerialName("task_running") val taskRunning: Boolean? = null,
)

@Serializable
data class McpConnections(
    val items: List<McpConnection>? = null,
)

@Serializable
data class McpConnection(
    @SerialName("auth_type") val authType: String? = null,
    @SerialName("bot_id") val botId: String? = null,
    val config: JsonObject? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val id: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("last_probed_at") val lastProbedAt: String? = null,
    val name: String? = null,
    val status: String? = null,
    @SerialName("status_message") val statusMessage: String? = null,
    @SerialName("tools_cache") val toolsCache: List<McpTool>? = null,
    val type: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class McpTool(
    val description: String? = null,
    val inputSchema: JsonObject? = null,
    val name: String? = null,
)

@Serializable
data class InstalledApps(
    @SerialName("dependency_catalog_stale") val dependencyCatalogStale: Boolean? = null,
    val items: List<InstalledApp>? = null,
    @SerialName("workspace_state") val workspaceState: String? = null,
)

@Serializable
data class InstalledApp(
    @SerialName("app_id") val appId: String? = null,
    val author: SupermarketAuthor? = null,
    @SerialName("available_revision") val availableRevision: String? = null,
    @SerialName("available_version") val availableVersion: String? = null,
    val category: String? = null,
    @SerialName("category_name") val categoryName: String? = null,
    val connectors: List<AppConnectorItem>? = null,
    val dependencies: List<AppDependencyItem>? = null,
    val description: String? = null,
    val homepage: String? = null,
    val icon: SupermarketSkillIcon? = null,
    @SerialName("installation_id") val installationId: String? = null,
    @SerialName("installed_at") val installedAt: String? = null,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    val license: String? = null,
    val name: String? = null,
    val reason: String? = null,
    @SerialName("registry_id") val registryId: String? = null,
    val repository: String? = null,
    val revision: String? = null,
    val skills: List<AppSkillItem>? = null,
    val status: String? = null,
    val tags: List<String>? = null,
    val translations: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val version: String? = null,
)

@Serializable
data class SupermarketAuthor(
    val email: String? = null,
    val name: String? = null,
)

@Serializable
data class AppConnectorItem(
    @SerialName("connection_id") val connectionId: String? = null,
    val connector: Connector? = null,
    val required: Boolean? = null,
    val status: String? = null,
    val type: String? = null,
)

@Serializable
data class Connector(
    val alias: String? = null,
    @SerialName("auth_method") val authMethod: String? = null,
    @SerialName("connection_id") val connectionId: String? = null,
    @SerialName("connector_type") val connectorType: String? = null,
    val enabled: Boolean? = null,
    val status: String? = null,
)

@Serializable
data class AppDependencyItem(
    val dependency: WorkspaceDependencyItem? = null,
    val id: String? = null,
    val shared: Boolean? = null,
)

@Serializable
data class WorkspaceDependencyItem(
    val actions: List<String>? = null,
    val category: String? = null,
    @SerialName("definition_revision") val definitionRevision: String? = null,
    val description: String? = null,
    val icon: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val id: String? = null,
    @SerialName("image_version") val imageVersion: String? = null,
    @SerialName("install_path") val installPath: String? = null,
    @SerialName("installed_version") val installedVersion: String? = null,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_error_code") val lastErrorCode: String? = null,
    @SerialName("latest_version") val latestVersion: String? = null,
    val name: String? = null,
    val overlay: Boolean? = null,
    @SerialName("platform_reason") val platformReason: String? = null,
    @SerialName("platform_supported") val platformSupported: Boolean? = null,
    @SerialName("previous_version") val previousVersion: String? = null,
    val provides: List<String>? = null,
    @SerialName("registry_id") val registryId: String? = null,
    val retired: Boolean? = null,
    val source: String? = null,
    val status: String? = null,
    val translations: JsonObject? = null,
    @SerialName("update_available") val updateAvailable: Boolean? = null,
)

@Serializable
data class WorkspaceDependencyTranslation(
    val description: String? = null,
    val name: String? = null,
)

@Serializable
data class SupermarketSkillIcon(
    @SerialName("brand_color") val brandColor: String? = null,
    val card: SupermarketSkillIconAsset? = null,
    val dark: SupermarketSkillIconAsset? = null,
    val detail: SupermarketSkillIconAsset? = null,
)

@Serializable
data class SupermarketSkillIconAsset(
    @SerialName("content_type") val contentType: String? = null,
    val digest: String? = null,
    val size: Long? = null,
)

@Serializable
data class AppSkillItem(
    val description: String? = null,
    val icon: SupermarketSkillIcon? = null,
    @SerialName("install_id") val installId: String? = null,
    val name: String? = null,
    @SerialName("skill_id") val skillId: String? = null,
)

@Serializable
data class SupermarketAppTranslation(
    val description: String? = null,
    val name: String? = null,
)

@Serializable
data class AppInstallEvent(
    val args: JsonObject? = null,
    val code: String? = null,
    val data: String? = null,
    val detail: String? = null,
    val id: String? = null,
    val kind: String? = null,
    val message: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    val status: String? = null,
    val stream: String? = null,
    val type: String? = null,
    val version: String? = null,
)

@Serializable
data class MarketApps(
    val data: List<MarketApp>? = null,
    val limit: Long? = null,
    val page: Long? = null,
    val total: Long? = null,
)

@Serializable
data class MarketApp(
    @SerialName("app_id") val appId: String? = null,
    val author: SupermarketAuthor? = null,
    val categories: List<SupermarketAppSkillCategory>? = null,
    val category: String? = null,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("connector_count") val connectorCount: Long? = null,
    val connectors: List<SupermarketAppConnector>? = null,
    val dependencies: List<String>? = null,
    @SerialName("dependency_count") val dependencyCount: Long? = null,
    val description: String? = null,
    val homepage: String? = null,
    val icon: SupermarketSkillIcon? = null,
    val license: String? = null,
    val name: String? = null,
    @SerialName("registry_id") val registryId: String? = null,
    val repository: String? = null,
    @SerialName("schema_version") val schemaVersion: String? = null,
    @SerialName("skill_count") val skillCount: Long? = null,
    val tags: List<String>? = null,
    val translations: JsonObject? = null,
    val version: String? = null,
)

@Serializable
data class SupermarketAppSkillCategory(
    val id: String? = null,
    val name: String? = null,
    @SerialName("skill_count") val skillCount: Long? = null,
)

@Serializable
data class SupermarketAppConnector(
    val required: Boolean? = null,
    val type: String? = null,
)

@Serializable
data class MarketSkills(
    val data: List<MarketSkill>? = null,
    val limit: Long? = null,
    val page: Long? = null,
    val total: Long? = null,
)

@Serializable
data class MarketSkill(
    @SerialName("app_id") val appId: String? = null,
    val artifact: SupermarketSkillArtifact? = null,
    val author: SupermarketAuthor? = null,
    val category: String? = null,
    @SerialName("category_name") val categoryName: String? = null,
    val description: String? = null,
    val files: List<String>? = null,
    val homepage: String? = null,
    val icon: SupermarketSkillIcon? = null,
    @SerialName("install_id") val installId: String? = null,
    val name: String? = null,
    @SerialName("registry_id") val registryId: String? = null,
    @SerialName("schema_version") val schemaVersion: String? = null,
    @SerialName("skill_id") val skillId: String? = null,
    val source: SupermarketSkillSource? = null,
    @SerialName("source_category") val sourceCategory: String? = null,
    val tags: List<String>? = null,
)

@Serializable
data class SupermarketSkillArtifact(
    @SerialName("archive_size") val archiveSize: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
    val digest: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("file_count") val fileCount: Long? = null,
    val format: String? = null,
    val size: Long? = null,
    @SerialName("uncompressed_size") val uncompressedSize: Long? = null,
)

@Serializable
data class SupermarketSkillSource(
    val path: String? = null,
    val repository: String? = null,
    val revision: String? = null,
    val type: String? = null,
)

@Serializable
data class SkillsList(
    val skills: List<InstalledSkill>? = null,
)

@Serializable
data class InstalledSkill(
    @SerialName("app_id") val appId: String? = null,
    val content: String? = null,
    val deletable: Boolean? = null,
    val description: String? = null,
    val editable: Boolean? = null,
    val managed: Boolean? = null,
    val metadata: JsonObject? = null,
    val name: String? = null,
    val raw: String? = null,
    @SerialName("registry_id") val registryId: String? = null,
    @SerialName("shadowed_by") val shadowedBy: String? = null,
    @SerialName("skill_id") val skillId: String? = null,
    @SerialName("source_kind") val sourceKind: String? = null,
    @SerialName("source_path") val sourcePath: String? = null,
    @SerialName("source_root") val sourceRoot: String? = null,
    val state: String? = null,
)

@Serializable
data class RuntimeControls(
    val capabilities: RuntimeControlCapabilities? = null,
    val commands: List<RuntimeCommand>? = null,
    val modes: RuntimeModeState? = null,
    @SerialName("plan_mode") val planMode: RuntimeModeState? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class RuntimeControlCapabilities(
    val compact: Boolean? = null,
    val goal: Boolean? = null,
    @SerialName("permission_modes") val permissionModes: Boolean? = null,
    @SerialName("plan_mode") val planMode: Boolean? = null,
)

@Serializable
data class RuntimeCommand(
    @SerialName("completed_text") val completedText: String? = null,
    val description: String? = null,
    @SerialName("i18n_key") val i18nKey: String? = null,
    @SerialName("input_hint") val inputHint: String? = null,
    val kind: RuntimeCommandKind? = null,
    val name: String? = null,
    @SerialName("running_text") val runningText: String? = null,
)

typealias RuntimeCommandKind = JsonObject

@Serializable
data class RuntimeModeState(
    @SerialName("apply_on_next_turn") val applyOnNextTurn: Boolean? = null,
    @SerialName("available_modes") val availableModes: List<RuntimeMode>? = null,
    @SerialName("current_mode_id") val currentModeId: String? = null,
    val kind: String? = null,
    val supported: Boolean? = null,
)

@Serializable
data class RuntimeMode(
    val description: String? = null,
    @SerialName("i18n_key") val i18nKey: String? = null,
    val icon: String? = null,
    val id: String? = null,
    val name: String? = null,
    val warning: Boolean? = null,
)

@Serializable
data class RuntimeGoal(
    val objective: String? = null,
    val status: String? = null,
    @SerialName("time_used_seconds") val timeUsedSeconds: Long? = null,
    @SerialName("token_budget") val tokenBudget: Long? = null,
    @SerialName("tokens_used") val tokensUsed: Long? = null,
)

@Serializable
data class AcpRuntimeStatus(
    @SerialName("acp_session_id") val acpSessionId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("available_commands") val availableCommands: List<AvailableCommandInfo>? = null,
    @SerialName("default_model_id") val defaultModelId: String? = null,
    val models: ModelState? = null,
    val modes: ModeState? = null,
    @SerialName("project_path") val projectPath: String? = null,
    val reasoning: ReasoningState? = null,
    @SerialName("runtime_id") val runtimeId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val state: String? = null,
)

@Serializable
data class AvailableCommandInfo(
    val description: String? = null,
    @SerialName("input_hint") val inputHint: String? = null,
    val name: String? = null,
)

@Serializable
data class ModelState(
    @SerialName("available_models") val availableModels: List<ModelInfo>? = null,
    @SerialName("current_model_id") val currentModelId: String? = null,
    val supported: Boolean? = null,
)

@Serializable
data class ModelInfo(
    val description: String? = null,
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class ModeState(
    @SerialName("available_modes") val availableModes: List<ModeInfo>? = null,
    @SerialName("current_mode_id") val currentModeId: String? = null,
    val supported: Boolean? = null,
)

@Serializable
data class ModeInfo(
    val description: String? = null,
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class ReasoningState(
    @SerialName("available_efforts") val availableEfforts: List<ReasoningEffortInfo>? = null,
    @SerialName("current_effort") val currentEffort: String? = null,
    val supported: Boolean? = null,
)

@Serializable
data class ReasoningEffortInfo(
    val description: String? = null,
    val id: String? = null,
    val name: String? = null,
)
