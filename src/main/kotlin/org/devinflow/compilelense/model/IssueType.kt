package org.devinflow.compilelense.model

enum class IssueType(val displayName: String) {
    UNRESOLVED_IMPORT("Unresolved import"),
    CLASS_NOT_FOUND("Class not found"),
    MISSING_DEPENDENCY("Missing dependency"),
    COMPILATION_ERROR("Compilation error"),
}
