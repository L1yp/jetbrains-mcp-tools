@file:Suppress("UnstableApiUsage")

package com.l1yp.tool

import com.google.gson.JsonObject
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.l1yp.configuration.RunConfigurationExposureCatalog
import com.l1yp.execution.RunStateTracker
import com.l1yp.mcp.McpTool
import com.l1yp.mcp.McpToolCallResult
import com.l1yp.mcp.McpToolDefinition
import java.util.concurrent.atomic.AtomicReference

internal class RestartRunConfigurationTool : McpTool {
    override val definition = McpToolDefinition(
        name = "restart_run_configuration",
        description = "Starts or restarts one persistent Run Configuration returned by get_restartable_run_configurations.",
        inputSchema = schema(
            """{
                "type":"object",
                "properties":{"configurationName":{"type":"string","minLength":1}},
                "required":["configurationName"],
                "additionalProperties":false
            }""".trimIndent(),
        ),
        outputSchema = schema(
            """{
                "type":"object",
                "properties":{
                    "configurationName":{"type":"string"},
                    "action":{"type":"string","enum":["start_scheduled","restart_scheduled"]},
                    "previousRunningInstances":{"type":"integer"},
                    "target":{"type":"string"},
                    "projectName":{"type":"string"}
                },
                "required":["configurationName","action","previousRunningInstances","target","projectName"],
                "additionalProperties":false
            }""".trimIndent(),
        ),
        annotations = schema(
            """{"readOnlyHint":false,"destructiveHint":true,"idempotentHint":false,"openWorldHint":false}""",
        ),
    )

    override fun call(project: Project, arguments: JsonObject): McpToolCallResult {
        if (arguments.keySet() != setOf("configurationName")) {
            throw IllegalArgumentException("Expected exactly one argument: configurationName")
        }
        val configurationName = arguments.get("configurationName")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            .orEmpty()
        if (configurationName.isEmpty()) {
            throw IllegalArgumentException("configurationName must not be blank")
        }

        return runCatching { onEdt { restart(project, configurationName) } }
            .getOrElse { error ->
                McpToolCallResult.error(
                    "Failed to restart '$configurationName': ${error.message ?: error.javaClass.simpleName}",
                )
            }
    }

    private fun restart(project: Project, configurationName: String): McpToolCallResult {
        val allMatchingSettings = RunManager.getInstance(project).allSettings.filter { it.name == configurationName }
        if (allMatchingSettings.isEmpty()) {
            return McpToolCallResult.error("Run Configuration '$configurationName' was not found")
        }
        val matchingSettings = allMatchingSettings.filter { RunConfigurationExposureCatalog.isExposed(project, it) }
        if (matchingSettings.isEmpty()) {
            return McpToolCallResult.error(
                "Run Configuration '$configurationName' is not exposed by MCP Toolbox settings",
            )
        }
        if (matchingSettings.size > 1) {
            return McpToolCallResult.error(
                "Run Configuration name '$configurationName' is ambiguous (${matchingSettings.size} matches)",
            )
        }

        val settings = matchingSettings.single()
        val executor = DefaultRunExecutor.getRunExecutorInstance()
            ?: return McpToolCallResult.error("The IDE does not provide the default Run executor")
        if (ProgramRunner.getRunner(executor.id, settings.configuration) == null) {
            return McpToolCallResult.error("No Run runner supports configuration '$configurationName'")
        }

        val runningHandlers = project.service<RunStateTracker>().runningHandlers(settings.uniqueID)
        if (runningHandlers.size > 1) {
            return McpToolCallResult.error(
                "Run Configuration '$configurationName' has ${runningHandlers.size} running instances; " +
                    "stop extra instances before restarting",
            )
        }

        val activeTarget = ExecutionTargetManager.getActiveTarget(project)
        if (!ExecutionTargetManager.canRun(settings.configuration, activeTarget)) {
            return McpToolCallResult.error(
                "Run Configuration '$configurationName' cannot run on target '${activeTarget.displayName}'",
            )
        }

        val wasRunning = runningHandlers.isNotEmpty()
        ExecutionManager.getInstance(project).restartRunProfile(
            project,
            executor,
            activeTarget,
            settings,
            runningHandlers.singleOrNull(),
        )

        return McpToolCallResult.success(JsonObject().apply {
            addProperty("configurationName", configurationName)
            addProperty("action", if (wasRunning) "restart_scheduled" else "start_scheduled")
            addProperty("previousRunningInstances", runningHandlers.size)
            addProperty("target", activeTarget.displayName)
            addProperty("projectName", project.name)
        })
    }
}

private fun <T> onEdt(action: () -> T): T {
    val application = ApplicationManager.getApplication()
    if (application.isDispatchThread) return action()

    val result = AtomicReference<Result<T>>()
    application.invokeAndWait {
        result.set(runCatching(action))
    }
    return result.get().getOrThrow()
}
