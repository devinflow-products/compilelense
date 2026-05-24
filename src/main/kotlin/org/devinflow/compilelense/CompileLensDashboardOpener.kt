package org.devinflow.compilelense

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import org.devinflow.compilelense.editor.CompileLensVirtualFile

object CompileLensDashboardOpener {
    fun open(project: Project) {
        if (project.isDisposed) return
        val file = CompileLensVirtualFile.getOrCreate(project)
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}
