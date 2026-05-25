package org.devinflow.compilelense.scan

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile

internal object CompileLensPaths {

    fun normalize(virtualFile: VirtualFile): String =
        FileUtil.toSystemIndependentName(virtualFile.canonicalPath ?: virtualFile.path)

    fun normalize(path: String): String =
        FileUtil.toSystemIndependentName(path)
}
