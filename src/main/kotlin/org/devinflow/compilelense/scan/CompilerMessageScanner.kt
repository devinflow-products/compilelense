package org.devinflow.compilelense.scan

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.compiler.CompilerMessage
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import org.devinflow.compilelense.model.IssueType
import org.devinflow.compilelense.model.UncompiledIssue
import java.util.Locale

/**
 * Reads javac messages from a finished compile and maps them to [UncompiledIssue] rows.
 * Must run under a read action — use [collect] from any thread.
 */
internal object CompilerMessageScanner {

    fun collect(project: Project, compileContext: CompileContext): List<UncompiledIssue> {
        if (project.isDisposed) return emptyList()
        return ReadAction.compute<List<UncompiledIssue>, RuntimeException> {
            collectUnderReadAction(project, compileContext)
        }
    }

    private fun collectUnderReadAction(project: Project, compileContext: CompileContext): List<UncompiledIssue> {
        val messages = compileContext.getMessages(CompilerMessageCategory.ERROR) ?: return emptyList()
        val results = ArrayList<UncompiledIssue>()
        val seen = HashSet<String>()

        for (message in messages) {
            val virtualFile = message.virtualFile ?: continue
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: continue
            val line = lineNumber(message, virtualFile).coerceAtLeast(1)
            val detail = sanitize(message.message)
            if (detail.isBlank()) continue

            val key = "${virtualFile.path}:$line:${detail.lowercase(Locale.getDefault())}"
            if (!seen.add(key)) continue

            val fileName = virtualFile.name
            val className = psiFile.classes.firstOrNull()?.name ?: fileName.removeSuffix(".java")
            val issueType = classify(detail)
            results += UncompiledIssue(
                className = className,
                fileName = fileName,
                packageName = psiFile.packageName.ifBlank { "(default package)" },
                issueType = issueType,
                issueSummary = issueType.displayName,
                issueDetail = detail,
                moduleName = com.intellij.openapi.module.ModuleUtil.findModuleForFile(virtualFile, project)?.name
                    ?: project.name,
                lineNumber = line,
                virtualFilePath = virtualFile.path,
                hasFixSuggestion = issueType == IssueType.MISSING_DEPENDENCY || issueType == IssueType.UNRESOLVED_IMPORT,
            )
        }
        return results
    }

    private fun lineNumber(message: CompilerMessage, virtualFile: VirtualFile): Int {
        val navigatable = message.navigatable
        if (navigatable is OpenFileDescriptor && navigatable.file == virtualFile) {
            return navigatable.line + 1
        }
        return 1
    }

    private fun sanitize(raw: String): String =
        raw.replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun classify(detail: String): IssueType {
        val lower = detail.lowercase(Locale.getDefault())
        if ("import" in lower && ("cannot find symbol" in lower || "cannot resolve" in lower)) {
            return IssueType.UNRESOLVED_IMPORT
        }
        if ("package" in lower && "does not exist" in lower) {
            return IssueType.MISSING_DEPENDENCY
        }
        return IssueType.COMPILATION_ERROR
    }
}
