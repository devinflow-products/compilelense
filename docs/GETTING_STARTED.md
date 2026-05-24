# CompileLens — Getting Started

CompileLens is an IntelliJ IDEA plugin that gives you a **live dashboard of Java classes that do not compile** across your open projects. It aggregates editor diagnostics, the Problems view, and compiler output so you can see what is broken, where it lives, and jump to the error in one click.

---

## Requirements

| Requirement | Details |
|-------------|---------|
| IDE | IntelliJ IDEA **2025.3+** (Ultimate or compatible build with Java support) |
| Language | **Java** source files in the project |
| Plugins | Java support and Kotlin plugin (bundled dependencies) |
| Project type | Maven, Gradle, or plain Java modules with source roots |

CompileLens works on **Java** (`.java`) files in source roots. It does not analyze Kotlin-only sources.

---

## Installation

### From source (developers)

1. Clone or open this repository in IntelliJ IDEA.
2. Wait for Gradle sync to finish.
3. Run the **Run Plugin** configuration (`.run/Run Plugin.run.xml`), or from the project root:

   ```bash
   ./gradlew runIde
   ```

   On Windows:

   ```bat
   gradlew.bat runIde
   ```

4. A sandbox IDE opens with CompileLens loaded. Open a Java project to try it.

### From JetBrains Marketplace

When published, install **CompileLens** from **Settings → Plugins → Marketplace**, then restart the IDE.

---

## Open the dashboard

You can open CompileLens in any of these ways:

1. **Tool window** — Click **CompileLens** on the right tool window stripe.
2. **View menu** — **View → CompileLens**
3. **Tools menu** — **Tools → CompileLens**

The dashboard opens as a docked tool window with a sidebar (summary and filters) and a main table (classes with issues).

---

## First run

When you open CompileLens for the first time on a project:

1. The plugin waits until the project finishes indexing (**smart mode**).
2. An initial **re-scan** runs: it rebuilds open projects and refreshes the issue list.
3. The sidebar shows how many classes have problems, how many modules are affected, and when the last scan completed.

After that, the dashboard **updates automatically** as you edit code—fixed files drop off the list without a manual refresh.

---

## Dashboard layout

```
┌─────────────────────┬──────────────────────────────────────────┐
│  Sidebar            │  Main panel                              │
│                     │                                          │
│  • Total count      │  Search + sort                           │
│  • Module breakdown │  ┌────────────────────────────────────┐  │
│  • Last scan time   │  │ Class │ Folder │ Line numbers      │  │
│  • Re-scan button   │  └────────────────────────────────────┘  │
│  • Module filter    │                                          │
│  • Open files only  │                                          │
└─────────────────────┴──────────────────────────────────────────┘
```

### Sidebar

| Control | Purpose |
|---------|---------|
| **Summary** | Number of classes with compilation issues and affected modules |
| **Fix suggestions** | Count of issues that may be fixable (e.g. imports, dependencies) |
| **Last scan** | Relative time since the dashboard was last updated |
| **Refresh (↻)** | Full **re-scan**: rebuilds all open projects and refreshes the list |
| **Module filter** | Show issues for one module or **All Modules** |
| **Show only open files** | Limit the table to files currently open in the editor |

### Main table

| Column | Description |
|--------|-------------|
| **Class** | Class name, package, and file name |
| **Folder** | Parent folder of the source file (click to reveal in Project view) |
| **Location** | Line numbers with errors (click a line to jump to the editor) |

**Navigation:**

- **Double-click** a row → open the file at the first error line.
- **Click a line number** → open the file at that line.
- **Click the folder** column → select the file’s parent in the project tree.

Use the **search** box to filter by class name, package, folder, or module. Sort by **folder** or **class** name.

---

## Live updates

CompileLens keeps the dashboard in sync without manual steps:

- **While you type** — After you fix an error, the row is removed once the IDE finishes code analysis (usually within about a second).
- **On save / VFS changes** — Saving files or changing build files (`pom.xml`, Gradle scripts) triggers a refresh.
- **Problems view** — When a problem appears or disappears in the IDE, the dashboard updates.
- **After build** — Compiler (`javac`) messages from the last rebuild are merged in; when a file is clean in the editor, stale build errors for that file are cleared automatically.

You only need **Re-scan** when you want a full **Rebuild Project** pass on all open projects (for example after large dependency or module changes).

---

## Issue types

CompileLens classifies problems to help you prioritize:

| Type | Typical cause |
|------|-----------------|
| **Unresolved import** | Missing or wrong `import` statement |
| **Class not found** | Referenced type does not exist on the classpath |
| **Missing dependency** | Package or symbol missing (often needs a library or module dependency) |
| **Compilation error** | Syntax errors, type mismatches, and other javac/IDE errors |

Rows that may benefit from adding a dependency or fixing imports are counted in the sidebar **fix suggestions** summary.

---

## Recommended workflow

1. Open your Java project and wait for indexing to finish.
2. Open **CompileLens** from the right tool window stripe.
3. Review the list—start with modules that have the most issues (sidebar breakdown).
4. Click a row or line number to jump to the code and fix the error.
5. Confirm the row disappears from the dashboard as the red underline clears.
6. Use **Re-scan** after changing `pom.xml` / `build.gradle` or adding modules if the list looks out of date.

---

## Troubleshooting

| Symptom | What to try |
|---------|-------------|
| Dashboard is empty but the editor shows errors | Wait for indexing; click **Re-scan**; ensure the file is under a Java source root |
| Fixed file still listed | Wait a moment for analysis to finish; if it persists, click **Re-scan** |
| No updates while editing | Confirm the file is `.java` and the project is not in dumb mode (indexing bar) |
| Re-scan takes a long time | Normal for large multi-module projects; it runs **Build → Rebuild Project** on each open project |
| Plugin not visible | **Settings → Plugins** — enable CompileLens; restart the IDE |

**Logs:** In a development sandbox, CompileLens writes to the IDE log with the logger name `CompileLens`. Search `idea.log` for `CompileLens` to debug scan behavior.

---

## Privacy and scope

- CompileLens analyzes **local project data** only (PSI, editor highlights, compiler output in the IDE).
- It scans **open projects** in the current IDE window and Java sources in configured source roots.
- No data is sent to external services by the plugin itself.

---

## For plugin developers

| Task | Command / location |
|------|-------------------|
| Run plugin in sandbox | `./gradlew runIde` or **Run Plugin** run configuration |
| Build | `./gradlew build` |
| Plugin ID | `org.devinflow.compilelense` |
| Entry tool window | `CompileLens` (right stripe) |
| Sources | `src/main/kotlin/org/devinflow/compilelense/` |

See the root [README.md](../README.md) for Gradle project structure and publishing notes.

---

## Quick reference

| Action | How |
|--------|-----|
| Open dashboard | Right stripe **CompileLens**, or **View / Tools → CompileLens** |
| Jump to error | Click line number or double-click row |
| Filter by module | Sidebar module dropdown |
| Search | Main panel search field |
| Full rebuild + refresh | Sidebar **↻ Re-scan** |

---

**CompileLens** · Plugin ID `org.devinflow.compilelense` · Version 1.0.0-SNAPSHOT
