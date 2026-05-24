package org.devinflow.compilelense.editor

import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import org.devinflow.compilelense.CompileLensFileType

class CompileLensVirtualFile(projectName: String) :
    LightVirtualFile("Uncompiled Classes", CompileLensFileType, projectName) {

    override fun isWritable(): Boolean = false
    override fun isDirectory(): Boolean = false

    companion object {
        fun getOrCreate(project: Project): CompileLensVirtualFile =
            project.getService(CompileLensDashboardFileHolder::class.java).virtualFile
    }
}
