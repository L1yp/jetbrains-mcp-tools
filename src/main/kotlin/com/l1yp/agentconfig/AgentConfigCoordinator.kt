package com.l1yp.agentconfig

import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.l1yp.agentconfig.adapters.CodexConfigAdapter
import com.l1yp.agentconfig.adapters.KimiCodeConfigAdapter
import com.l1yp.agentconfig.adapters.MiMoCodeConfigAdapter
import com.l1yp.agentconfig.adapters.OhMyPiConfigAdapter
import com.l1yp.agentconfig.adapters.OpenCodeConfigAdapter
import com.l1yp.agentconfig.adapters.QoderConfigAdapter
import com.l1yp.agentconfig.adapters.TraeConfigAdapter
import com.l1yp.agentconfig.adapters.ZCodeConfigAdapter
import com.l1yp.agentconfig.adapters.ManualAgentCatalog
import com.l1yp.logging.McpToolboxLogService
import com.l1yp.mcp.McpProjectTokenService
import com.l1yp.mcp.McpProtocol
import com.l1yp.mcp.McpToolSettings
import com.l1yp.mcp.ToolRegistry
import org.jetbrains.ide.BuiltInServerManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class AgentSyncStatus {
    NOT_DETECTED,
    NOT_SELECTED,
    CONFIG_MISSING,
    PENDING,
    SYNCED,
    GIT_TRACKED,
    PARSE_FAILED,
    USER_MODIFIED,
    LEASE_HELD,
    NEEDS_NEW_SESSION,
    CLIENT_KNOWN_ISSUE,
}

internal data class AgentConfigView(
    val adapter: AgentConfigAdapter,
    val detection: AgentDetection,
    val location: AgentConfigLocation,
    val selected: Boolean,
    val status: AgentSyncStatus,
    val detail: String? = null,
)

@Service(Service.Level.PROJECT)
internal class AgentConfigCoordinator(private val project: Project) : Disposable {
    val adapters: List<AgentConfigAdapter> = listOf(
        CodexConfigAdapter(),
        TraeConfigAdapter(),
        QoderConfigAdapter(),
        OhMyPiConfigAdapter(),
        KimiCodeConfigAdapter(),
        ZCodeConfigAdapter(),
        OpenCodeConfigAdapter(),
        MiMoCodeConfigAdapter(),
    )

    private val detectionService = AgentDetectionService()
    private val started = AtomicBoolean(false)
    private val lastSynchronizedPort = AtomicInteger(-1)
    private var leaseWasOwned = false
    private var monitor: ScheduledFuture<*>? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        monitor = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            ::monitorSafely,
            1,
            MONITOR_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    fun selectedAgentIds(): Set<String> = project.service<AgentConfigSettings>().selectedAgentIds()

    fun updateSelectedAgentIds(ids: Set<String>) {
        val knownIds = adapters.mapTo(mutableSetOf()) { it.id }
        ManualAgentCatalog.agents.mapTo(knownIds) { it.id }
        project.service<AgentConfigSettings>().updateSelectedAgentIds(ids.intersect(knownIds))
    }

    fun views(): List<AgentConfigView> {
        val selected = selectedAgentIds()
        val lease = project.service<ProjectConfigLease>().acquire()
        return adapters.map { adapter ->
            val detection = detectionService.detect(adapter, project)
            val location = adapter.locate(project)
            val isSelected = adapter.id in selected
            val (status, detail) = when {
                lease !is ProjectLeaseStatus.Owned -> AgentSyncStatus.LEASE_HELD to leaseDetail(lease)
                !detection.detected -> AgentSyncStatus.NOT_DETECTED to detection.evidence
                !isSelected -> AgentSyncStatus.NOT_SELECTED to null
                else -> adjustClientSpecificStatus(
                    adapter,
                    statusFromChange(adapter.preview(project, endpointFor(adapter))),
                )
            }
            AgentConfigView(adapter, detection, location, isSelected, status, detail)
        }
    }

    fun preview(adapterId: String): ConfigChange {
        val adapter = adapter(adapterId) ?: return ConfigChange.Blocked(adapterId, null, "Unknown Agent adapter")
        val lease = project.service<ProjectConfigLease>().acquire()
        if (lease !is ProjectLeaseStatus.Owned) {
            return ConfigChange.Blocked(adapterId, adapter.locate(project).path, leaseDetail(lease))
        }
        return adapter.preview(project, endpointFor(adapter))
    }

    fun sync(
        adapterId: String,
        requireSelfTest: Boolean = true,
        confirmUserChanges: Boolean = false,
    ): ApplyResult {
        val adapter = adapter(adapterId) ?: return ApplyResult.Failed(null, "Unknown Agent adapter")
        val lease = project.service<ProjectConfigLease>().acquire()
        if (lease !is ProjectLeaseStatus.Owned) {
            return ApplyResult.Failed(adapter.locate(project).path, leaseDetail(lease))
        }
        if (requireSelfTest) {
            val selfTest = McpEndpointSelfTest.test(project)
            if (selfTest != null) return ApplyResult.Failed(adapter.locate(project).path, selfTest)
        }
        val endpoint = endpointFor(adapter)
        val change = if (confirmUserChanges && adapter is FileAgentConfigAdapter) {
            adapter.previewAfterUserConfirmation(project, endpoint)
        } else {
            adapter.preview(project, endpoint)
        }
        return adapter.apply(change)
    }

    fun syncSelected(requireSelfTest: Boolean = true): Map<String, ApplyResult> {
        val log = project.service<McpToolboxLogService>()
        val automaticIds = adapters.mapTo(mutableSetOf()) { it.id }
        val selected = selectedAgentIds().intersect(automaticIds)
        if (selected.isEmpty()) {
            lastSynchronizedPort.set(BuiltInServerManager.getInstance().port)
            log.info("Agent 同步", "没有选中的自动配置 Agent，跳过同步")
            return emptyMap()
        }
        log.info("Agent 同步", "开始同步 ${selected.size} 个 Agent：${selected.joinToString()}")
        if (requireSelfTest) {
            val selfTest = McpEndpointSelfTest.test(project)
            if (selfTest != null) {
                log.error("Agent 同步", "Endpoint 自检失败，已取消同步：$selfTest")
                return selected.associateWith { id -> ApplyResult.Failed(adapter(id)?.locate(project)?.path, selfTest) }
            }
        }
        val results = linkedMapOf<String, ApplyResult>()
        selected.forEach { id -> results[id] = sync(id, requireSelfTest = false) }
        if (results.values.none { it is ApplyResult.Failed }) {
            lastSynchronizedPort.set(BuiltInServerManager.getInstance().port)
            log.success("Agent 同步", "已完成 ${results.size} 个 Agent 的同步")
        } else {
            val failedIds = results.filterValues { it is ApplyResult.Failed }.keys
            log.error("Agent 同步", "同步失败：${failedIds.joinToString()}")
        }
        return results
    }

    fun remove(adapterId: String): ApplyResult {
        val adapter = adapter(adapterId) ?: return ApplyResult.Failed(null, "Unknown Agent adapter")
        val lease = project.service<ProjectConfigLease>().acquire()
        if (lease !is ProjectLeaseStatus.Owned) {
            return ApplyResult.Failed(adapter.locate(project).path, leaseDetail(lease))
        }
        return adapter.remove(project)
    }

    fun rotateToken(): Map<String, ApplyResult> {
        project.service<McpToolboxLogService>().warning("安全配置", "正在轮换项目 Token")
        project.service<McpProjectTokenService>().rotate()
        return syncSelected(requireSelfTest = true)
    }

    fun endpointFor(adapter: AgentConfigAdapter): McpEndpoint {
        val settings = project.service<AgentConfigSettings>()
        val recordedName = settings.ownership(adapter.id)?.serverName
        val serverName = ServerNameSelector.select(
            recordedName = recordedName,
            existingNames = runCatching { adapter.existingServerNames(project) }.getOrDefault(emptySet()),
            projectPath = project.basePath.orEmpty(),
        )
        return endpoint(serverName, adapter.id)
    }

    fun endpointForManualConfiguration(diagnosticClientName: String = "manual"): McpEndpoint =
        endpoint("jetbrains_tools", diagnosticClientName)

    private fun endpoint(serverName: String, diagnosticClientName: String): McpEndpoint {
        val port = BuiltInServerManager.getInstance().port
        val token = project.service<McpProjectTokenService>().token()
        return McpEndpoint(
            serverName = serverName,
            url = "http://127.0.0.1:$port${McpProtocol.ENDPOINT_PATH}",
            authorizationHeader = "Bearer $token",
            protocolVersion = McpProtocol.VERSION,
            startupTimeoutMillis = 10_000,
            toolTimeoutMillis = 120_000,
            enabledTools = project.service<McpToolSettings>().enabledToolNames(),
            diagnosticClientName = diagnosticClientName,
        )
    }

    override fun dispose() {
        monitor?.cancel(false)
        monitor = null
    }

    private fun monitorSafely() {
        if (project.isDisposed) return
        runCatching {
            val owned = project.service<ProjectConfigLease>().acquire() is ProjectLeaseStatus.Owned
            val port = BuiltInServerManager.getInstance().port
            if (owned && (!leaseWasOwned || port != lastSynchronizedPort.get())) {
                syncSelected(requireSelfTest = true)
            }
            leaseWasOwned = owned
        }.onFailure { error ->
            project.service<McpToolboxLogService>().error(
                "Agent 自动同步",
                "后台同步异常：${error.message ?: error.javaClass.simpleName}",
            )
            LOG.warn("Unable to synchronize MCP Agent configurations", error)
        }
    }

    private fun statusFromChange(change: ConfigChange): Pair<AgentSyncStatus, String?> = when (change) {
        is ConfigChange.Unchanged -> AgentSyncStatus.SYNCED to null
        is ConfigChange.Ready -> {
            val status = if (change.createdFileByPlugin) AgentSyncStatus.CONFIG_MISSING else AgentSyncStatus.PENDING
            status to null
        }
        is ConfigChange.Blocked -> when {
            change.reason.contains("tracked by Git", ignoreCase = true) -> AgentSyncStatus.GIT_TRACKED to change.reason
            change.reason.contains("modified by the user", ignoreCase = true) ->
                AgentSyncStatus.USER_MODIFIED to change.reason
            else -> AgentSyncStatus.PARSE_FAILED to change.reason
        }
    }

    private fun adjustClientSpecificStatus(
        adapter: AgentConfigAdapter,
        status: Pair<AgentSyncStatus, String?>,
    ): Pair<AgentSyncStatus, String?> {
        if (status.first != AgentSyncStatus.SYNCED) return status
        return when (adapter.id) {
            KimiCodeConfigAdapter.ID -> AgentSyncStatus.NEEDS_NEW_SESSION to adapter.reloadInstruction()
            MiMoCodeConfigAdapter.ID -> AgentSyncStatus.CLIENT_KNOWN_ISSUE to adapter.reloadInstruction()
            else -> status
        }
    }

    private fun adapter(id: String): AgentConfigAdapter? = adapters.firstOrNull { it.id == id }

    private fun leaseDetail(status: ProjectLeaseStatus): String = when (status) {
        ProjectLeaseStatus.Owned -> ""
        is ProjectLeaseStatus.HeldByAnotherProcess -> status.metadata ?: "Configuration lease is held by another IDE"
        is ProjectLeaseStatus.Unavailable -> status.reason
    }

    private companion object {
        const val MONITOR_INTERVAL_SECONDS = 30L
        val LOG = logger<AgentConfigCoordinator>()
    }
}

internal object ServerNameSelector {
    private const val DEFAULT_NAME = "jetbrains_tools"

    fun select(recordedName: String?, existingNames: Set<String>, projectPath: String): String {
        if (!recordedName.isNullOrBlank()) return recordedName
        if (DEFAULT_NAME !in existingNames) return DEFAULT_NAME
        val suffix = MessageDigest.getInstance("SHA-256")
            .digest(projectPath.toByteArray(StandardCharsets.UTF_8))
            .take(3)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "${DEFAULT_NAME}_$suffix"
    }
}

internal object McpEndpointSelfTest {
    fun test(project: Project): String? {
        val log = project.service<McpToolboxLogService>()
        val startedAt = System.nanoTime()
        log.info("Endpoint 自检", "开始执行 initialize → tools/list")
        val failure = runCatching<String?> {
            val port = BuiltInServerManager.getInstance().port
            val uri = URI("http://127.0.0.1:$port${McpProtocol.ENDPOINT_PATH}")
            val token = project.service<McpProjectTokenService>().token()
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val initialize = send(
                client,
                uri,
                token,
                $$"""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"jetbrains-mcp-tools-self-test","version":"1"}}}""",
                includeProtocolVersion = false,
                diagnosticClientName = SELF_TEST_CLIENT_NAME,
            )
            require(initialize.statusCode() == 200) { "initialize returned HTTP ${initialize.statusCode()}" }
            val initializePayload = JsonParser.parseString(initialize.body()).asJsonObject
            require(initializePayload.getAsJsonObject("result")?.get("protocolVersion")?.asString == McpProtocol.VERSION) {
                "initialize returned an unexpected protocol version"
            }
            val tools = send(
                client,
                uri,
                token,
                """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
                includeProtocolVersion = true,
                diagnosticClientName = SELF_TEST_CLIENT_NAME,
            )
            require(tools.statusCode() == 200) { "tools/list returned HTTP ${tools.statusCode()}" }
            val names = JsonParser.parseString(tools.body()).asJsonObject
                .getAsJsonObject("result")
                .getAsJsonArray("tools")
                .map { it.asJsonObject.get("name").asString }
            require(names == project.service<McpToolSettings>().enabledToolNames()) {
                "tools/list returned an unexpected tool catalog"
            }
            null
        }.getOrElse { error ->
            "MCP endpoint self-test failed: ${error.message ?: error.javaClass.simpleName}"
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        if (failure == null) {
            log.success("Endpoint 自检", "自检通过，耗时 ${elapsedMillis}ms")
        } else {
            log.error("Endpoint 自检", "$failure，耗时 ${elapsedMillis}ms")
        }
        return failure
    }

    private fun send(
        client: HttpClient,
        uri: URI,
        token: String,
        body: String,
        includeProtocolVersion: Boolean,
        diagnosticClientName: String,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Authorization", "Bearer $token")
            .header(McpProtocol.DIAGNOSTIC_CLIENT_HEADER, diagnosticClientName)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (includeProtocolVersion) builder.header("MCP-Protocol-Version", McpProtocol.VERSION)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private const val SELF_TEST_CLIENT_NAME = "mcp-toolbox-self-test"
}

internal class AgentConfigStartupActivity : com.intellij.openapi.startup.ProjectActivity {
    override suspend fun execute(project: Project) {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode || application.isHeadlessEnvironment) return
        project.service<AgentConfigCoordinator>().start()
    }
}
