package org.devinflow.compilelense

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Custom icons used in CompileLens UI surfaces (tool window stripe, etc.).
 *
 * IntelliJ resolves a `_dark.svg` sibling automatically when the IDE is on a dark
 * theme, so we only reference the light path here.
 */
object CompileLensIcons {
    /** 13×13 stripe icon for the tool window. */
    val ToolWindowError: Icon = load("/icons/compileLensError.svg")
    val ToolWindowClean: Icon = load("/icons/compileLensClean.svg")

    /** 16×16 hero badge for the dashboard summary card (above "N Uncompiled Java Classes"). */
    val SummaryError: Icon = load("/icons/compileLensErrorBadge.svg")
    val SummaryClean: Icon = load("/icons/compileLensCleanBadge.svg")

    private fun load(path: String): Icon = IconLoader.getIcon(path, CompileLensIcons::class.java)
}
