package com.github.untalluisnava.nbclaude.project;

import org.netbeans.api.project.Project;
import org.netbeans.spi.project.ui.support.ProjectCustomizer;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;

import javax.swing.JComponent;
import java.io.File;
import java.util.List;

/**
 * Adds a "Claude Code" category to the project Properties dialog, letting the user override {@link
 * ClaudeTerminalDirectoryPreference} per project without going through the ask-dialog.
 *
 * <p>There is no single layer path that covers every project type (unlike {@code Projects/Actions}
 * for popup menus), so this same provider is registered once per supported project type: Maven,
 * Gradle, and HTML5/JS.
 */
@ProjectCustomizer.CompositeCategoryProvider.Registrations({
  @ProjectCustomizer.CompositeCategoryProvider.Registration(
      projectType = "org-netbeans-modules-maven",
      position = 950),
  @ProjectCustomizer.CompositeCategoryProvider.Registration(
      projectType = "org-netbeans-modules-gradle",
      position = 950),
  @ProjectCustomizer.CompositeCategoryProvider.Registration(
      projectType = "org.netbeans.modules.web.clientproject",
      position = 950)
})
public final class ClaudeTerminalCustomizerProvider
    implements ProjectCustomizer.CompositeCategoryProvider {

  @Override
  public ProjectCustomizer.Category createCategory(Lookup context) {
    // This package already has a Bundle.properties written by the GUI builder (for the panel's
    // label), so the key is added there directly instead of via @Messages, which would otherwise
    // collide with it as a duplicate declaration.
    String displayName =
        NbBundle.getMessage(ClaudeTerminalCustomizerProvider.class, "LBL_ClaudeTerminalCategory");
    return ProjectCustomizer.Category.create("claude-terminal", displayName, null);
  }

  @Override
  public JComponent createComponent(ProjectCustomizer.Category category, Lookup context) {
    Project project = context.lookup(Project.class);
    ClaudeTerminalCustomizerPanel panel = new ClaudeTerminalCustomizerPanel();
    if (project != null) {
      List<File> candidates = ClaudeTerminalDirectoryPreference.findCandidateDirectories(project);
      String stored = ClaudeTerminalDirectoryPreference.readStoredValue(project);
      panel.setCandidates(candidates, stored);
      category.setStoreListener(
          e ->
              ClaudeTerminalDirectoryPreference.writeStoredValue(
                  project, panel.getSelectedValue()));
    }
    return panel;
  }
}
