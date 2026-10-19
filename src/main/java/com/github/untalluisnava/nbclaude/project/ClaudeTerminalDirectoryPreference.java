package com.github.untalluisnava.nbclaude.project;

import org.netbeans.api.project.Project;
import org.netbeans.spi.project.AuxiliaryProperties;
import org.openide.filesystems.FileUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-project preference for where the Claude Code terminal should open. Candidate directories are
 * discovered by walking up from the project directory and collecting every ancestor that contains a
 * {@code .claude} configuration folder (plus the project directory itself), so a project nested
 * inside a configured monorepo always offers that monorepo root as an option instead of only the
 * project's own folder.
 *
 * <p>The resolved preference is stored as a private (not shared/VCS) project property: either an
 * absolute directory path, the {@link #ASK} sentinel (always prompt), or absent (use the computed
 * default).
 */
public final class ClaudeTerminalDirectoryPreference {

  /** Sentinel stored value meaning "always ask, don't default to a single directory". */
  public static final String ASK = "ASK";

  private static final String PROPERTY_KEY = "claude.terminal.directory";

  private ClaudeTerminalDirectoryPreference() {}

  /**
   * Candidate working directories for this project, nearest first: the project directory itself,
   * followed by every ancestor directory that contains a {@code .claude} folder (closest ancestor
   * first).
   */
  public static List<File> findCandidateDirectories(Project project) {
    List<File> candidates = new ArrayList<>();
    File projectDir = FileUtil.toFile(project.getProjectDirectory());
    if (projectDir == null) {
      return candidates;
    }
    candidates.add(projectDir);
    File parent = projectDir.getParentFile();
    while (parent != null) {
      if (new File(parent, ".claude").isDirectory()) {
        candidates.add(parent);
      }
      parent = parent.getParentFile();
    }
    return candidates;
  }

  /**
   * The explicitly stored preference for this project: an absolute directory path, {@link #ASK}, or
   * {@code null} if the user has never set one.
   */
  public static String readStoredValue(Project project) {
    AuxiliaryProperties properties = project.getLookup().lookup(AuxiliaryProperties.class);
    return properties == null ? null : properties.get(PROPERTY_KEY, false);
  }

  /** Pass {@code null} to clear the stored preference. */
  public static void writeStoredValue(Project project, String value) {
    AuxiliaryProperties properties = project.getLookup().lookup(AuxiliaryProperties.class);
    if (properties != null) {
      properties.put(PROPERTY_KEY, value, false);
    }
  }

  /**
   * Resolves the directory to open right now, or {@code null} if the caller should prompt the user
   * (either because that was explicitly requested, or because nothing is stored yet and there is a
   * real choice to make).
   */
  public static File resolve(Project project) {
    List<File> candidates = findCandidateDirectories(project);
    if (candidates.isEmpty()) {
      return null;
    }
    String stored = readStoredValue(project);
    if (stored == null) {
      // No preference yet: only interrupt the user if there is an actual choice beyond the
      // project's own folder.
      return candidates.size() > 1 ? null : candidates.get(0);
    }
    if (ASK.equals(stored)) {
      return null;
    }
    File storedDirectory = new File(stored);
    if (storedDirectory.isDirectory()) {
      return storedDirectory;
    }
    // The remembered directory is gone (e.g. .claude was removed); fall back as if unset.
    return candidates.size() > 1 ? null : candidates.get(0);
  }
}
