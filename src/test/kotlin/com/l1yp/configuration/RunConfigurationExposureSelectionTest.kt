package com.l1yp.configuration

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunConfigurationExposureSelectionTest {
    @Test
    fun `hides Maven and Gradle before user configures selection`() {
        val selection = RunConfigurationExposureSelection(configured = false, exposedTypeIds = emptySet())

        assertFalse(selection.isExposed("GradleRunConfiguration"))
        assertFalse(selection.isExposed("MavenRunConfiguration"))
        assertTrue(selection.isExposed("SpringBootApplicationConfigurationType"))
    }

    @Test
    fun `uses strict allowlist after user configures selection`() {
        val selection = RunConfigurationExposureSelection(
            configured = true,
            exposedTypeIds = setOf("SpringBootApplicationConfigurationType", "MavenRunConfiguration"),
        )

        assertTrue(selection.isExposed("SpringBootApplicationConfigurationType"))
        assertTrue(selection.isExposed("MavenRunConfiguration"))
        assertFalse(selection.isExposed("GradleRunConfiguration"))
        assertFalse(selection.isExposed("Application"))
    }
}
