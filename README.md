# Claude Code bridge for Netbeans (nb-claude-code-bridge)

An **Apache NetBeans** module (plugin) that brings the [Claude Code](https://docs.anthropic.com/en/docs/claude-code) CLI into the IDE, similar to the Claude Code extension for VS Code.

It embeds a real terminal (JediTerm + pty4j) in a dockable NetBeans panel that runs `claude`, and adds actions to send files as context, open the terminal in the right directory for each project, and automatically open in the editor the files Claude creates or modifies.

Source code, releases and issue tracker: <https://github.com/un-tal-luis-nava/nb-claude-code-bridge>

## Features

### Integrated Claude Code terminal
- A **Claude Code** panel docked in the *Output* area, hosting a full terminal (`xterm-256color`) built on JediTerm and pty4j.
- Runs `claude` through a *login shell* (`/bin/sh -lc` on Linux/macOS, `cmd.exe` on Windows), so it honors the `PATH` set up by your shell profiles (nvm, asdf, etc.).
- When `claude` exits (or is not installed), the terminal drops into your login shell instead of leaving a dead panel; run `claude` again from there to start a new session.
- If `claude` is not found on the `PATH`, it shows a warning (once per session). The check runs in the background, so opening the terminal never blocks the IDE.
- Closing the panel or exiting the IDE terminates the terminal process (a JVM *shutdown hook* acts as a backstop).

#### Terminal environment
The `claude` process inherits the IDE's environment, plus these variables set by the plugin:

| Variable | Value | Purpose |
|---|---|---|
| `TERM` | `xterm-256color` | Terminal type; supports at least 256 colors. |
| `COLORTERM` | `truecolor` | Advertises 24-bit RGB color, so Claude Code renders its full palette instead of a 256-color approximation. |

Other common color variables are not set by the plugin, but you can try them by launching `claude` manually from a shell in the terminal, e.g. `FORCE_COLOR=2 claude` (`1` = 16 colors, `2` = 256, `3` = truecolor) or `NO_COLOR=1 claude` to disable colors.

### Matches the NetBeans editor color scheme
> [!NOTE]
> Currently disabled: the terminal uses JediTerm's default colors and font. To enable it, set `USE_EDITOR_COLOR_SCHEME = true` in `ClaudeTerminalTopComponent`, rebuild, and **restart NetBeans** (see [Development notes](#development-notes)).

The terminal takes its background, foreground, selection, search highlight and font from the active editor profile (`Tools` → `Options` → `Fonts & Colors`). Its 16 ANSI colors are derived from the editor's token colors: each ANSI slot keeps its canonical hue (red stays red, green stays green) but uses a token color from the scheme when one has a similar hue, adjusted for readable contrast against the background.

To have Claude Code draw itself entirely in those colors, pick one of its **ANSI colors only** themes with `/theme` (dark or light, matching your scheme). Color changes apply live; a font change applies the next time the terminal starts.

### Actions
| Action | Location | What it does |
|---|---|---|
| **Claude Code Terminal** | `Window` → `Claude Code Terminal` | Opens/focuses the terminal in the current working directory (the user's home by default). |
| **Open Claude Code Terminal Here** | Project context menu | Opens the terminal in the project directory (or an ancestor with a `.claude` configuration) and enables watching for edited files. |
| **Send File to Claude Code** | `Edit` → `Send File to Claude Code` and the *File* toolbar | Types `@/path/to/file ` into the terminal to add the active file as context (paths containing spaces are quoted: `@"/path/with spaces/file"`). No Enter is sent, so you can review the prompt before submitting it. |

### Per-project working directory
When the terminal is opened from a project, the plugin looks for candidate directories:
1. The project's own directory.
2. Every ancestor directory containing a `.claude` folder (useful in monorepos), closest first.

If there is more than one candidate and no stored preference, a dialog lets you choose, with a **Remember this choice for this project** option. If the terminal is already running in a different directory, it is restarted in the new one.

The preference can also be set under **Project Properties → Claude Code** (Maven, Gradle and HTML5/JS projects), choosing a specific directory or **Ask every time**. It is stored as a private project property (not shared through version control).

### Auto-opening files edited by Claude
While the terminal is open from a project, a `WatchService` monitors **that project's** directory (even when the terminal runs in a monorepo ancestor) and opens in the editor any file that gets created or modified.
- Ignores build/dependency directories (`target`, `build`, `dist`, `out`, `node_modules`, `bin`) and any hidden file or folder (names starting with `.`).
- Debounces for 1.5 s so the same file isn't reopened on repeated write events.
- Resolves symlinks and automatically registers newly created subdirectories.

## Requirements
- **Apache NetBeans** compatible with the `RELEASE310` APIs.
- **JDK 21** or newer, both to build and to run NetBeans (the module targets `maven.compiler.release=21`).
- **Maven 3** to build.
- **Claude Code CLI** installed and reachable from a login shell:
  ```sh
  npm install -g @anthropic-ai/claude-code
  ```

## Build and install
```sh
mvn clean package
```
This produces the module `target/nb-claude-code-bridge-1.0.0-SNAPSHOT.nbm`. To install it in NetBeans:

1. `Tools` → `Plugins` → **Downloaded** tab → **Add Plugins...**
2. Select the generated `.nbm` file and install it.

You can also open the project in NetBeans and use **Run** to launch an IDE instance with the module loaded.

## Development notes
- **Install/Reload in Development IDE** swaps the module's classes in place, which is enough for code-only changes.
- When a change adds a new NetBeans module dependency to `pom.xml`, *Reload* is not enough: the module's class loader keeps the dependencies it was first enabled with, and the new classes fail with `ClassNotFoundException`/`NoClassDefFoundError`. Restart NetBeans so it reads the updated manifest.
- If the editor flags imports from a newly added dependency ("Package from transitive module dependency referenced…"), right-click the project → **Reload POM**.

## Code layout
```
src/main/java/com/github/untalluisnava/nbclaude/
├── actions/
│   ├── OpenClaudeTerminalAction.java            # Window → Claude Code Terminal
│   ├── OpenClaudeTerminalForProjectAction.java  # Project context menu
│   └── SendFileToClaudeAction.java              # Sends @file to the terminal
├── project/
│   ├── ClaudeTerminalDirectoryPreference.java   # Candidates and per-project preference
│   ├── ClaudeTerminalCustomizerProvider.java    # Project Properties category
│   └── ClaudeTerminalCustomizerPanel.java       # UI for that category
├── terminal/
│   ├── ClaudeEditedFilesWatcher.java            # Opens edited files in the editor
│   ├── NetBeansTerminalSettingsProvider.java    # Terminal colors/font from the editor scheme
│   └── PtyProcessTtyConnector.java              # pty4j ↔ JediTerm bridge
└── ui/
    └── ClaudeTerminalTopComponent.java          # Panel hosting the embedded terminal
```

## Author
**Luis Manuel Nava Flores** — [@un-tal-luis-nava](https://github.com/un-tal-luis-nava) · <lm.nava.flores@outlook.com>

Bug reports and feature requests are welcome in the [issue tracker](https://github.com/un-tal-luis-nava/nb-claude-code-bridge/issues).

## License
Licensed under the [Apache License, Version 2.0](LICENSE).

`PtyProcessTtyConnector` is adapted from [JetBrains/jediterm](https://github.com/JetBrains/jediterm) and remains under the LGPL 3.0. The module bundles third-party libraries under their own licenses: JediTerm (LGPL 3.0), pty4j (EPL 1.0) and JNA (LGPL 2.1 or later / Apache 2.0).
