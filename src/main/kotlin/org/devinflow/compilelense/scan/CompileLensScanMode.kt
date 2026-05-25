package org.devinflow.compilelense.scan

/**
 * Controls how aggressively CompileLens runs background IDE analysis before collecting issues.
 */
internal enum class CompileLensScanMode {
    /** Analyze every Java source file that has not been confirmed clean (startup, rebuild, indexing). */
    FULL_WORKSPACE,

    /** Re-analyze only changed, open, or previously tracked files; still reports issues from all sources. */
    INCREMENTAL,
}
