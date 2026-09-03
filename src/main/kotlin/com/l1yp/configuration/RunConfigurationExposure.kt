package com.l1yp.configuration

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "McpRunConfigurationExposureSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
internal class RunConfigurationExposureSettings :
    PersistentStateComponent<RunConfigurationExposureState> {
    private var currentState = RunConfigurationExposureState()

    override fun getState(): RunConfigurationExposureState = currentState

    override fun loadState(state: RunConfigurationExposureState) {
        currentState = state
    }

    fun selection(): RunConfigurationExposureSelection = RunConfigurationExposureSelection(
        configured = currentState.configured,
        exposedTypeIds = currentState.exposedTypeIds.toSet(),
    )

    fun updateExposedTypeIds(typeIds: Set<String>) {
        currentState = RunConfigurationExposureState(
            configured = true,
            exposedTypeIds = typeIds.sorted().toMutableList(),
        )
    }
}

internal data class RunConfigurationExposureState(
    var configured: Boolean = false,
    var exposedTypeIds: MutableList<String> = mutableListOf(),
)

internal data class RunConfigurationExposureSelection(
    val configured: Boolean,
    val exposedTypeIds: Set<String>,
) {
    fun isExposed(typeId: String): Boolean =
        if (configured) typeId in exposedTypeIds else typeId !in DEFAULT_HIDDEN_TYPE_IDS

    companion object {
        internal val DEFAULT_HIDDEN_TYPE_IDS = setOf(
            "GradleRunConfiguration",
            "MavenRunConfiguration",
        )
    }
}

internal object RunConfigurationExposureCatalog {
    fun availableTypes(project: Project): List<RunConfigurationTypeDescriptor> =
        persistentSettings(project)
            .groupBy { it.type.id }
            .map { (typeId, settings) ->
                RunConfigurationTypeDescriptor(
                    id = typeId,
                    displayName = settings.first().type.displayName,
                    configurationCount = settings.size,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, RunConfigurationTypeDescriptor::displayName))

    fun exposedSettings(project: Project): List<RunnerAndConfigurationSettings> {
        val selection = project.service<RunConfigurationExposureSettings>().selection()
        return persistentSettings(project).filter { selection.isExposed(it.type.id) }
    }

    fun isExposed(project: Project, settings: RunnerAndConfigurationSettings): Boolean =
        !settings.isTemporary &&
            project.service<RunConfigurationExposureSettings>().selection().isExposed(settings.type.id)

    private fun persistentSettings(project: Project): List<RunnerAndConfigurationSettings> =
        RunManager.getInstance(project).allSettings.filterNot(RunnerAndConfigurationSettings::isTemporary)
}

internal data class RunConfigurationTypeDescriptor(
    val id: String,
    val displayName: String,
    val configurationCount: Int,
)
