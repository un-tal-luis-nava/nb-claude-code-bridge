package com.github.untalluisnava.nbclaude.ui;

import com.github.untalluisnava.nbclaude.terminal.ClaudeEditedFilesWatcher;
import com.github.untalluisnava.nbclaude.terminal.NetBeansTerminalSettingsProvider;
import com.github.untalluisnava.nbclaude.terminal.PtyProcessTtyConnector;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.util.ImageUtilities;
import org.openide.util.NbBundle.Messages;
import org.openide.util.RequestProcessor;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dockable panel embedding a real terminal (JediTerm + pty4j) that runs the {@code claude} CLI,
 * similar to the integrated terminal used by the Claude Code extension for VS Code.
 */
@TopComponent.Description(
    preferredID = "ClaudeTerminalTopComponent",
    persistenceType = TopComponent.PERSISTENCE_NEVER)
@TopComponent.Registration(mode = "output", openAtStartup = false)
@Messages({
  "CTL_ClaudeTerminalAction=Claude Code Terminal",
  "CTL_ClaudeTerminalTopComponent=Claude Code",
  "HINT_ClaudeTerminalTopComponent=Terminal running the Claude Code CLI",
  "MSG_ClaudeCliNotFound=The 'claude' CLI was not found on PATH. Install Claude Code "
      + "(e.g. 'npm install -g @anthropic-ai/claude-code') and make sure it is reachable from a "
      + "login shell. Opening a plain shell instead."
})
public final class ClaudeTerminalTopComponent extends TopComponent {

  private static final Logger LOG = Logger.getLogger(ClaudeTerminalTopComponent.class.getName());
  private static final String PREFERRED_ID = "ClaudeTerminalTopComponent";
  private static final String ICON_PATH =
      "com/github/untalluisnava/nbclaude/resources/claude16.png";

  // Backstop for when the IDE exits without going through componentClosed() (e.g. File > Exit
  // while the panel is still open): a JVM shutdown hook still kills the process tree.
  private static volatile PtyProcess runningProcess = null;

  // Avoids re-popping the "claude not found" dialog every time the terminal (re)starts in the
  // same IDE session.
  private static volatile boolean missingCliWarningShown = false;

  private static final RequestProcessor CLI_CHECK =
      new RequestProcessor(ClaudeTerminalTopComponent.class.getName() + ".cliCheck");

  // Temporarily disabled: when false, the terminal uses JediTerm's default colors and font instead
  // of the NetBeans editor color scheme. Being a compile-time constant, the disabled branch is
  // dropped from the bytecode, so NetBeansTerminalSettingsProvider (and the editor-settings
  // modules it needs) is never loaded.
  private static final boolean USE_EDITOR_COLOR_SCHEME = false;

  static {
    registerShutdownHook();
  }

  // Kept out of the static initializer itself: reading a mutable static field from code written
  // directly inside "static { ... }" trips NetBeans' "Usage of static non-final variable during
  // initialization" hint, even though this read only actually happens later (at JVM shutdown).
  private static void registerShutdownHook() {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  PtyProcess process = runningProcess;
                  if (process != null && process.isAlive()) {
                    process.destroy();
                  }
                },
                "claude-terminal-shutdown"));
  }

  private JediTermWidget terminalWidget;
  private File workingDirectory = new File(System.getProperty("user.home"));

  // Narrower than workingDirectory when the terminal's cwd is a monorepo ancestor: only files
  // under this directory get auto-opened as Claude edits them. Null when the terminal wasn't
  // opened from a specific project (e.g. the generic Window menu action), in which case nothing
  // is auto-opened.
  private File watchScope;
  private ClaudeEditedFilesWatcher watcher;
  private NetBeansTerminalSettingsProvider settingsProvider;

  public ClaudeTerminalTopComponent() {
    setLayout(new BorderLayout());
    setName(Bundle.CTL_ClaudeTerminalTopComponent());
    setToolTipText(Bundle.HINT_ClaudeTerminalTopComponent());
    setIcon(ImageUtilities.loadImage(ICON_PATH));
  }

  /** Finds the singleton instance, creating it (but not opening it) if needed. */
  public static synchronized ClaudeTerminalTopComponent findInstance() {
    TopComponent win = WindowManager.getDefault().findTopComponent(PREFERRED_ID);
    if (win instanceof ClaudeTerminalTopComponent) {
      return (ClaudeTerminalTopComponent) win;
    }
    if (win != null) {
      LOG.log(
          Level.WARNING, "There seem to be multiple components with the '{0}' ID.", PREFERRED_ID);
    }
    return new ClaudeTerminalTopComponent();
  }

  /**
   * Ensures the terminal is open, visible and focused, starting the {@code claude} process the
   * first time it is shown (in the current working directory, or the last one requested via {@link
   * #openAt}).
   */
  public void openAndActivate() {
    SwingUtilities.invokeLater(
        () -> {
          if (!isOpened()) {
            open();
          }
          requestActive();
        });
  }

  /**
   * Opens the terminal rooted at {@code directory}, with no file-watching (the caller has no
   * specific project to scope it to). If a session is already running elsewhere, it is restarted at
   * the new location.
   */
  public void openAt(File directory) {
    openAt(directory, null);
  }

  /**
   * Opens the terminal rooted at {@code directory}. While it runs, any file created or modified
   * under {@code watchScope} (typically the invoking project's own directory, which may be narrower
   * than {@code directory} itself when the terminal's cwd is a shared monorepo ancestor) is opened
   * in the editor. Pass {@code null} to disable watching. If a session is already running
   * elsewhere, it is restarted at the new location.
   */
  public void openAt(File directory, File watchScope) {
    SwingUtilities.invokeLater(
        () -> {
          boolean directoryChanged = !directory.equals(workingDirectory);
          boolean watchScopeChanged = !java.util.Objects.equals(watchScope, this.watchScope);
          workingDirectory = directory;
          this.watchScope = watchScope;
          if (isOpened() && terminalWidget != null) {
            if (directoryChanged) {
              restartTerminal();
            } else if (watchScopeChanged) {
              // The cwd didn't change (so the terminal process itself doesn't need restarting),
              // but a different project now owns the watch scope: swap the watcher on its own.
              stopWatcher();
              startWatcherIfNeeded();
            }
          }
          if (!isOpened()) {
            open();
          }
          requestActive();
        });
  }

  /**
   * Writes text into the terminal's standard input, as if the user had typed it. Does not send a
   * trailing newline, so the user can review the text (e.g. an "@file" mention) before pressing
   * Enter.
   */
  public void sendText(String text) {
    openAndActivate();
    SwingUtilities.invokeLater(
        () -> {
          try {
            if (terminalWidget != null) {
              terminalWidget.getTtyConnector().write(text);
            }
          } catch (IOException ex) {
            LOG.log(Level.WARNING, "Could not send text to the Claude Code terminal", ex);
          }
        });
  }

  @Override
  protected void componentOpened() {
    if (terminalWidget == null) {
      terminalWidget = createTerminalWidget();
      add(terminalWidget, BorderLayout.CENTER);
      revalidate();
    }
  }

  @Override
  protected void componentClosed() {
    stopWatcher();
    if (terminalWidget != null) {
      terminalWidget.getTtyConnector().close();
      terminalWidget.close();
      remove(terminalWidget);
      terminalWidget = null;
      runningProcess = null;
    }
    disposeSettingsProvider();
  }

  @Override
  protected void componentActivated() {
    if (terminalWidget != null) {
      terminalWidget.requestFocusInWindow();
    }
  }

  @Override
  public int getPersistenceType() {
    return TopComponent.PERSISTENCE_NEVER;
  }

  private void restartTerminal() {
    stopWatcher();
    terminalWidget.getTtyConnector().close();
    terminalWidget.close();
    remove(terminalWidget);
    terminalWidget = createTerminalWidget();
    add(terminalWidget, BorderLayout.CENTER);
    revalidate();
  }

  private JediTermWidget createTerminalWidget() {
    disposeSettingsProvider();
    SettingsProvider provider = new DefaultSettingsProvider();
    if (USE_EDITOR_COLOR_SCHEME) {
      // Repaints in place when the editor color profile changes, rather than restarting the
      // terminal, which would end the running claude session.
      settingsProvider =
          new NetBeansTerminalSettingsProvider(
              () ->
                  SwingUtilities.invokeLater(
                      () -> {
                        if (terminalWidget != null) {
                          terminalWidget.repaint();
                        }
                      }));
      provider = settingsProvider;
    }
    JediTermWidget widget = new JediTermWidget(80, 24, provider);
    widget.setTtyConnector(createTtyConnector());
    widget.start();
    startWatcherIfNeeded();
    return widget;
  }

  private void disposeSettingsProvider() {
    if (settingsProvider != null) {
      settingsProvider.dispose();
      settingsProvider = null;
    }
  }

  private void startWatcherIfNeeded() {
    if (watchScope != null) {
      watcher = new ClaudeEditedFilesWatcher(watchScope);
      watcher.start();
    }
  }

  private void stopWatcher() {
    if (watcher != null) {
      watcher.stop();
      watcher = null;
    }
  }

  private TtyConnector createTtyConnector() {
    boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    warnIfClaudeMissing(windows);
    try {
      Map<String, String> env = new HashMap<>(System.getenv());
      env.put("TERM", "xterm-256color");
      // JediTerm renders 24-bit SGR colors; advertising it makes the claude CLI use its full
      // palette instead of downsampling to the 256-color approximation.
      env.put("COLORTERM", "truecolor");
      // Falls through to the user's shell whenever claude exits, not only when it fails (a normal
      // /exit used to leave a dead panel), and also when claude isn't installed at all.
      String[] command =
          windows
              ? new String[] {"cmd.exe", "/k", "claude"}
              : new String[] {"/bin/sh", "-lc", "claude; exec \"${SHELL:-/bin/sh}\" -l"};
      PtyProcess process =
          new PtyProcessBuilder()
              .setCommand(command)
              .setEnvironment(env)
              .setDirectory(workingDirectory.getAbsolutePath())
              .start();
      runningProcess = process;
      return new PtyProcessTtyConnector(process, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("Could not start the Claude Code CLI ('claude' on PATH)", ex);
    }
  }

  /**
   * Checks for the CLI in the background and warns (once per IDE session) if it is missing. Runs
   * off the EDT because spawning a login shell can take seconds with heavy shell profiles; the
   * terminal itself doesn't wait for it, since it falls back to a plain shell on its own.
   */
  private static void warnIfClaudeMissing(boolean windows) {
    if (missingCliWarningShown) {
      return;
    }
    CLI_CHECK.post(
        () -> {
          if (!isClaudeAvailable(windows) && !missingCliWarningShown) {
            missingCliWarningShown = true;
            DialogDisplayer.getDefault()
                .notifyLater(
                    new NotifyDescriptor.Message(
                        Bundle.MSG_ClaudeCliNotFound(), NotifyDescriptor.WARNING_MESSAGE));
          }
        });
  }

  /**
   * Resolves {@code claude} the same way the terminal itself will: through a login shell, since a
   * GUI-launched IDE often has a narrower PATH than an interactive terminal (e.g. nvm/asdf shims
   * added only by shell profile scripts).
   */
  private static boolean isClaudeAvailable(boolean windows) {
    try {
      ProcessBuilder builder =
          windows
              ? new ProcessBuilder("cmd.exe", "/c", "where claude")
              : new ProcessBuilder("/bin/sh", "-lc", "command -v claude");
      builder.redirectErrorStream(true);
      builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      Process process = builder.start();
      boolean finished = process.waitFor(3, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return true; // inconclusive (slow shell profile): don't warn over a timeout
      }
      return process.exitValue() == 0;
    } catch (IOException ex) {
      LOG.log(Level.WARNING, "Could not determine whether the Claude Code CLI is installed", ex);
      return true; // inconclusive: don't block the terminal over a check that itself failed
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return true;
    }
  }
}
