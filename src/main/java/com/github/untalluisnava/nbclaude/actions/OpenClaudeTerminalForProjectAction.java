package com.github.untalluisnava.nbclaude.actions;

import com.github.untalluisnava.nbclaude.project.ClaudeTerminalDirectoryPreference;
import com.github.untalluisnava.nbclaude.ui.ClaudeTerminalTopComponent;
import org.netbeans.api.project.Project;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;
import org.openide.filesystems.FileUtil;
import org.openide.util.ContextAwareAction;
import org.openide.util.ImageUtilities;
import org.openide.util.Lookup;
import org.openide.util.NbBundle.Messages;
import org.openide.util.actions.Presenter;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.border.EmptyBorder;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.List;

/**
 * Opens (or relocates) the Claude Code terminal at one of the candidate working directories for the
 * selected project: the project's own folder, or any ancestor folder that contains a {@code
 * .claude} configuration directory (see {@link ClaudeTerminalDirectoryPreference}).
 *
 * <p>Registered under {@code Projects/Actions}, this placeholder must implement {@link
 * ContextAwareAction}: NetBeans builds each project's popup menu from the node's own {@link
 * Lookup}, not from the IDE's globally focused component, so a plain {@link
 * java.awt.event.ActionListener} here would never see which project was right-clicked.
 */
@ActionID(
    category = "Window",
    id = "com.github.untalluisnava.nbclaude.actions.OpenClaudeTerminalForProjectAction")
@ActionRegistration(displayName = "#CTL_OpenClaudeTerminalForProjectAction", lazy = false)
@ActionReference(path = "Projects/Actions", position = 1450, separatorBefore = 1425)
@Messages({
  "CTL_OpenClaudeTerminalForProjectAction=Open Claude Code Terminal Here",
  "TITLE_ChooseTerminalDirectory=Claude Code Terminal",
  "LBL_ChooseTerminalDirectory=Where should the Claude Code terminal open?",
  "# {0} - project directory path",
  "LBL_ProjectDirectoryOption=This project ({0})",
  "# {0} - ancestor directory path",
  "LBL_AncestorDirectoryOption=Ancestor with .claude config ({0})",
  "LBL_RememberChoice=Remember this choice for this project"
})
public final class OpenClaudeTerminalForProjectAction extends AbstractAction
    implements ContextAwareAction {

  private static final String ICON_PATH =
      "com/github/untalluisnava/nbclaude/resources/claude16.png";

  @Override
  public void actionPerformed(ActionEvent e) {
    // Never invoked directly: the layer always dispatches through createContextAwareInstance.
  }

  @Override
  public Action createContextAwareInstance(Lookup context) {
    return new ContextAction(context);
  }

  private static final class ContextAction extends AbstractAction implements Presenter.Popup {

    private final Project project;

    ContextAction(Lookup context) {
      super(
          Bundle.CTL_OpenClaudeTerminalForProjectAction(),
          ImageUtilities.loadImageIcon(ICON_PATH, false));
      // Project popup menus hide action icons unless both the Swing icon property and this
      // NetBeans-specific key are set, and the action explicitly presents its own menu item.
      putValue("iconBase", ICON_PATH);
      project = context.lookup(Project.class);
      setEnabled(project != null);
    }

    @Override
    public JMenuItem getPopupPresenter() {
      return new JMenuItem(this);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      if (project == null) {
        return;
      }
      File resolved = ClaudeTerminalDirectoryPreference.resolve(project);
      if (resolved != null) {
        openDirectory(resolved);
        return;
      }
      List<File> candidates = ClaudeTerminalDirectoryPreference.findCandidateDirectories(project);
      if (candidates.isEmpty()) {
        return;
      }
      promptForDirectory(candidates);
    }

    private void promptForDirectory(List<File> candidates) {
      ButtonGroup group = new ButtonGroup();
      JRadioButton[] options = new JRadioButton[candidates.size()];

      JPanel panel = new JPanel();
      panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
      panel.setBorder(new EmptyBorder(6, 6, 6, 6));
      panel.add(new JLabel(Bundle.LBL_ChooseTerminalDirectory()));
      panel.add(javax.swing.Box.createVerticalStrut(8));

      for (int i = 0; i < candidates.size(); i++) {
        File candidate = candidates.get(i);
        String label =
            i == 0
                ? Bundle.LBL_ProjectDirectoryOption(candidate)
                : Bundle.LBL_AncestorDirectoryOption(candidate);
        JRadioButton option = new JRadioButton(label);
        group.add(option);
        panel.add(option);
        options[i] = option;
      }
      // Default to the closest ancestor with a .claude config, if any, since that is usually
      // the intended working context; fall back to the project directory otherwise.
      options[candidates.size() > 1 ? 1 : 0].setSelected(true);

      JCheckBox remember = new JCheckBox(Bundle.LBL_RememberChoice());
      panel.add(javax.swing.Box.createVerticalStrut(8));
      panel.add(remember);

      NotifyDescriptor descriptor =
          new NotifyDescriptor(
              panel,
              Bundle.TITLE_ChooseTerminalDirectory(),
              NotifyDescriptor.OK_CANCEL_OPTION,
              NotifyDescriptor.QUESTION_MESSAGE,
              null,
              NotifyDescriptor.OK_OPTION);

      if (DialogDisplayer.getDefault().notify(descriptor) != NotifyDescriptor.OK_OPTION) {
        return;
      }

      File chosen = candidates.get(0);
      for (int i = 0; i < options.length; i++) {
        if (options[i].isSelected()) {
          chosen = candidates.get(i);
          break;
        }
      }
      if (remember.isSelected()) {
        ClaudeTerminalDirectoryPreference.writeStoredValue(project, chosen.getAbsolutePath());
      }
      openDirectory(chosen);
    }

    private void openDirectory(File directory) {
      // Edits are only auto-opened within the project's own folder, even when the terminal's
      // cwd is a wider monorepo ancestor (e.g. because that's where the .claude config lives).
      File projectDirectory = FileUtil.toFile(project.getProjectDirectory());
      ClaudeTerminalTopComponent.findInstance().openAt(directory, projectDirectory);
    }
  }
}
