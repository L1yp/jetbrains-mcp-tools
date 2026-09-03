package com.l1yp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.annotations.NonNls
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

internal class McpGuideEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.fileType === McpGuideFileType

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        McpGuideFileEditor(project, file)

    @NonNls
    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        private const val EDITOR_TYPE_ID = "mcp.service.restart.guide"
        private const val GUIDE_TITLE = "MCP Toolbox 使用说明"
        private val GUIDE_FILE_KEY: Key<LightVirtualFile> = Key.create("mcp.service.restart.guide.file")

        fun open(project: Project): Boolean {
            if (project.isDefault || project.isDisposed) return false

            val file = project.getUserData(GUIDE_FILE_KEY)?.takeIf(VirtualFile::isValid)
                ?: object : LightVirtualFile(GUIDE_TITLE, McpGuideFileType, "") {
                    override fun getPath(): String = GUIDE_TITLE
                }.also { project.putUserData(GUIDE_FILE_KEY, it) }

            return FileEditorManager.getInstance(project)
                .openFile(file, true)
                .any { editor -> editor is McpGuideFileEditor }
        }
    }
}

private class McpGuideFileEditor(
    project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {
    private val statusPanel = McpStatusPanel(project)

    override fun getComponent(): JComponent = statusPanel.component

    override fun getPreferredFocusedComponent(): JComponent = statusPanel.component

    override fun getName(): String = "MCP Guide"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() = Unit
}

private object McpGuideFileType : FakeFileType() {
    override fun isMyFileType(file: VirtualFile): Boolean = file.fileType === this

    @NonNls
    override fun getName(): String = "McpToolboxGuide"

    override fun getDescription(): @NlsContexts.Label String = "MCP Toolbox 使用说明"

    override fun getIcon(): Icon = AllIcons.General.Information
}
