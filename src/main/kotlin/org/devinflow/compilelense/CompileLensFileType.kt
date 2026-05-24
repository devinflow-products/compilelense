package org.devinflow.compilelense

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.NlsContexts
import javax.swing.Icon

object CompileLensFileType : FileType {
    override fun getName(): String = "CompileLensDashboard"
    override fun getDescription(): @NlsContexts.Label String = "CompileLens Dashboard"
    override fun getDefaultExtension(): String = ""
    override fun getIcon(): Icon? = null
    override fun isBinary(): Boolean = false
}
