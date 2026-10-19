package com.github.untalluisnava.nbclaude.actions;

import com.github.untalluisnava.nbclaude.ui.ClaudeTerminalTopComponent;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.awt.StatusDisplayer;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.nodes.Node;
import org.openide.util.NbBundle.Messages;
import org.openide.windows.TopComponent;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

/**
 * Sends the currently active editor's file to the Claude Code terminal as an "@file" mention, so
 * the user can add it as context (similar to the "Add to Claude" file mentions of the VS Code
 * extension).
 */
@ActionID(
    category = "Edit",
    id = "com.github.untalluisnava.nbclaude.actions.SendFileToClaudeAction")
@ActionRegistration(
    displayName = "#CTL_SendFileToClaudeAction",
    iconBase = "com/github/untalluisnava/nbclaude/resources/claude16.png")
@ActionReferences({
  @ActionReference(path = "Menu/Edit", position = 1450, separatorBefore = 1425),
  @ActionReference(path = "Toolbars/File", position = 700)
})
@Messages({
  "CTL_SendFileToClaudeAction=Send File to Claude Code",
  "MSG_NoActiveFile=No active file to send to Claude Code."
})
public final class SendFileToClaudeAction implements ActionListener {

  @Override
  public void actionPerformed(ActionEvent e) {
    File file = findActiveFile();
    if (file == null) {
      StatusDisplayer.getDefault().setStatusText(Bundle.MSG_NoActiveFile());
      return;
    }
    ClaudeTerminalTopComponent.findInstance().sendText(toMention(file.getAbsolutePath()));
  }

  /**
   * An unquoted mention ends at the first whitespace, so a path containing spaces is wrapped in
   * double quotes; other paths are sent bare, which is how mentions are normally typed.
   */
  static String toMention(String path) {
    boolean needsQuotes = path.chars().anyMatch(Character::isWhitespace);
    return (needsQuotes ? "@\"" + path + "\"" : "@" + path) + " ";
  }

  private File findActiveFile() {
    Node[] nodes = TopComponent.getRegistry().getActivatedNodes();
    if (nodes.length == 0) {
      return null;
    }
    DataObject dataObject = nodes[0].getLookup().lookup(DataObject.class);
    if (dataObject == null) {
      return null;
    }
    FileObject fileObject = dataObject.getPrimaryFile();
    return FileUtil.toFile(fileObject);
  }
}
