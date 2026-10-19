/*
 * Adapted from JetBrains/jediterm (JediTerm/src/main/java/com/jediterm/pty/PtyProcessTtyConnector.java),
 * https://github.com/JetBrains/jediterm - licensed under LGPL 3.0.
 * jediterm-core/jediterm-ui, published by JetBrains, no longer ship a reusable pty connector class,
 * so this small adapter is vendored here instead of pulling in the whole (unpublished) demo module.
 */
package io.github.untalluisnava.nbclaude.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;

import java.nio.charset.Charset;

/**
 * Bridges a {@link PtyProcess} (pty4j) to JediTerm's {@link com.jediterm.terminal.TtyConnector}.
 */
public class PtyProcessTtyConnector extends ProcessTtyConnector {

  private final PtyProcess process;

  public PtyProcessTtyConnector(PtyProcess process, Charset charset) {
    super(process, charset, null);
    this.process = process;
  }

  @Override
  public void resize(TermSize termSize) {
    if (isConnected()) {
      process.setWinSize(new WinSize(termSize.getColumns(), termSize.getRows()));
    }
  }

  @Override
  public boolean isConnected() {
    return process.isAlive();
  }

  @Override
  public String getName() {
    return "Claude Code";
  }
}
