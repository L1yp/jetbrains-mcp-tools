package com.l1yp.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class McpOnboardingActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode || application.isHeadlessEnvironment) return

        synchronized(ONBOARDING_LOCK) {
            val properties = PropertiesComponent.getInstance()
            if (properties.getValue(ONBOARDING_VERSION_KEY) == ONBOARDING_VERSION) return
            properties.setValue(ONBOARDING_VERSION_KEY, ONBOARDING_VERSION)
        }

        application.invokeLater(
            { if (!project.isDisposed) McpGuideEditorProvider.open(project) },
            ModalityState.nonModal(),
            project.disposed,
        )
    }

    private companion object {
        const val ONBOARDING_VERSION_KEY = "com.l1yp.mcpTools.onboardingVersion"
        const val ONBOARDING_VERSION = "4"
        val ONBOARDING_LOCK = Any()
    }
}
