package org.devinflow.compilelense.editor

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class CompileLensDashboardFileHolder(private val project: Project) {
    val virtualFile: CompileLensVirtualFile = CompileLensVirtualFile(project.name)
}
