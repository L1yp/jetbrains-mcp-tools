package com.l1yp.execution

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
internal class RunStateTracker(project: Project) : Disposable {
    private val handlersByConfigurationId = ConcurrentHashMap<String, MutableSet<ProcessHandler>>()

    init {
        project.messageBus.connect(this).subscribe(
            ExecutionManager.EXECUTION_TOPIC,
            object : ExecutionListener {
                override fun processStarting(
                    executorId: String,
                    env: ExecutionEnvironment,
                    handler: ProcessHandler,
                ) {
                    env.runnerAndConfigurationSettings?.uniqueID?.let { configurationId ->
                        handlersByConfigurationId
                            .computeIfAbsent(configurationId) { ConcurrentHashMap.newKeySet() }
                            .add(handler)
                    }
                }

                override fun processTerminated(
                    executorId: String,
                    env: ExecutionEnvironment,
                    handler: ProcessHandler,
                    exitCode: Int,
                ) {
                    env.runnerAndConfigurationSettings?.uniqueID?.let { configurationId ->
                        val handlers = handlersByConfigurationId[configurationId] ?: return@let
                        handlers.remove(handler)
                        if (handlers.isEmpty()) {
                            handlersByConfigurationId.remove(configurationId, handlers)
                        }
                    }
                }
            },
        )
    }

    fun runningHandlers(configurationId: String): List<ProcessHandler> {
        val handlers = handlersByConfigurationId[configurationId] ?: return emptyList()
        handlers.removeIf(ProcessHandler::isProcessTerminated)
        if (handlers.isEmpty()) {
            handlersByConfigurationId.remove(configurationId, handlers)
            return emptyList()
        }
        return handlers.toList()
    }

    override fun dispose() {
        handlersByConfigurationId.clear()
    }
}

internal class RunStateTrackerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<RunStateTracker>()
    }
}
