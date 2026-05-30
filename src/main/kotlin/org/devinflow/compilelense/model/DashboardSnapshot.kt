package org.devinflow.compilelense.model

import java.time.Instant

data class DashboardSnapshot(
    val issues: List<UncompiledIssue>,
    val lastScan: Instant,
    /**
     * False when the most recent scan saw zero Java source roots across every open
     * project — typically because the folder was opened without being imported as a
     * Maven/Gradle/JPS project. Used by the dashboard to surface a one-line hint
     * instead of an empty table. Defaults to true so the bootstrap snapshot
     * ([Instant.EPOCH]) does not flash the hint before the first real scan runs.
     */
    val hasJavaSourceRoots: Boolean = true,
) {
    val uncompiledClasses: List<UncompiledClassRow> by lazy { UncompiledClassRow.fromIssues(issues) }

    val totalCount: Int get() = uncompiledClasses.size

    val moduleCounts: Map<String, Int>
        get() = uncompiledClasses.groupingBy { it.moduleName }.eachCount().toList().sortedByDescending { it.second }
            .associate { it.first to it.second }

    val fixSuggestionCount: Int
        get() = uncompiledClasses.count { it.hasFixSuggestion }

    val moduleCount: Int get() = moduleCounts.size
}
