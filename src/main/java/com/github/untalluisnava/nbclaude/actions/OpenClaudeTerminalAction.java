package com.github.untalluisnava.nbclaude.actions;

import com.github.untalluisnava.nbclaude.ui.ClaudeTerminalTopComponent;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;
import org.openide.util.NbBundle.Messages;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/** Opens the Claude Code terminal panel, docked in the "output" area. */
@ActionID(
    category = "Window",
    id = "com.github.untalluisnava.nbclaude.actions.OpenClaudeTerminalAction")
@ActionRegistration(
    displayName = "#CTL_ClaudeTerminalAction",
    iconBase = "com/github/untalluisnava/nbclaude/resources/claude16.png")
@ActionReference(path = "Menu/Window", position = 1300)
@Messages("CTL_ClaudeTerminalAction=Claude Code Terminal")
public final class OpenClaudeTerminalAction implements ActionListener {

  @Override
  public void actionPerformed(ActionEvent e) {
    ClaudeTerminalTopComponent.findInstance().openAndActivate();
  }
}
