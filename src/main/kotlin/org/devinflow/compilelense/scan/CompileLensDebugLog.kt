package org.devinflow.compilelense.scan

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

internal object CompileLensDebugLog {
    private val LOG = Logger.getInstance("CompileLens")

    fun info(project: Project, message: String) {
        LOG.info("[${project.name}] $message")
    }

    fun warn(project: Project, message: String) {
        LOG.warn("[${project.name}] $message")
    }

    fun warn(project: Project, message: String, throwable: Throwable) {
        LOG.warn("[${project.name}] $message", throwable)
    }
}
