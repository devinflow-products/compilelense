package org.devinflow.compilelense.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import org.devinflow.compilelense.ui.CompileLensDashboardPanel
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class CompileLensDashboardEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val dashboardPanel = CompileLensDashboardPanel(project)

    override fun getComponent(): JComponent = dashboardPanel
    override fun getPreferredFocusedComponent(): JComponent? = dashboardPanel
    override fun getName(): String = "CompileLens Dashboard"
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = !project.isDisposed
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun getFile(): VirtualFile = file
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun dispose() = dashboardPanel.dispose()
}
