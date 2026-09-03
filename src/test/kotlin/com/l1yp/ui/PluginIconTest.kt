package com.l1yp.ui

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginIconTest {
    @Test
    fun `plugin logos satisfy JetBrains Marketplace dimensions and size`() {
        listOf("pluginIcon.svg", "pluginIcon_dark.svg").forEach { fileName ->
            val path = Path.of("src/main/resources/META-INF", fileName)
            val root = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(path.toFile())
                .documentElement

            assertEquals("svg", root.tagName)
            assertEquals("40", root.getAttribute("width"))
            assertEquals("40", root.getAttribute("height"))
            assertEquals("0 0 40 40", root.getAttribute("viewBox"))
            assertTrue(Files.size(path) < 3 * 1024, "$fileName should stay below 3 KiB")
            assertEquals(0, root.getElementsByTagName("text").length)
        }
    }
}
