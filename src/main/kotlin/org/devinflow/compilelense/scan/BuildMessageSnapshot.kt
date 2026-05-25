package org.devinflow.compilelense.scan

/**
 * Lightweight compile error captured on the compile callback thread (EDT-safe).
 * [filePath] may be resolved later under a background read action.
 */
internal data class BuildMessageSnapshot(
    val fileName: String,
    val line: Int,
    val message: String,
    val filePath: String? = null,
)
