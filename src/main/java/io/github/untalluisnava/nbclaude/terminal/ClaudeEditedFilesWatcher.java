package io.github.untalluisnava.nbclaude.terminal;

import org.openide.cookies.OpenCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectNotFoundException;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches a project's directory tree while the Claude Code terminal is working in it, and opens in
 * the editor any file that gets created or modified there.
 *
 * <p>Scoped to a single project directory (not the terminal's actual working directory, which may
 * be a wider monorepo ancestor), and filtered to skip common build/VCS noise, since those are the
 * two things that would otherwise make this feature either irrelevant (wrong files) or unusable
 * (opens everything a build touches).
 */
public final class ClaudeEditedFilesWatcher {

  private static final Logger LOG = Logger.getLogger(ClaudeEditedFilesWatcher.class.getName());

  private static final Set<String> IGNORED_DIR_NAMES =
      Set.of("target", "build", "dist", "out", "node_modules", "bin");

  /**
   * Re-opening the same file within this window (editors often fire multiple write events for a
   * single save) is suppressed.
   */
  private static final long DEBOUNCE_MILLIS = 1500;

  private final Path root;
  private final Thread thread;
  private volatile WatchService watchService;
  private final ConcurrentHashMap<Path, Long> lastOpened = new ConcurrentHashMap<>();

  public ClaudeEditedFilesWatcher(File root) {
    this.root = resolveRealPath(root.toPath());
    this.thread = new Thread(this::run, "claude-terminal-file-watcher");
    this.thread.setDaemon(true);
  }

  /**
   * Resolves symlinks up front: {@link Files#walkFileTree} does not follow a symlink that is itself
   * the starting path, so a project directory that is (or sits under) a symlink would otherwise
   * register zero watches while looking like it started fine.
   */
  private static Path resolveRealPath(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException ex) {
      return path.toAbsolutePath().normalize();
    }
  }

  public void start() {
    thread.start();
  }

  public void stop() {
    WatchService service = watchService;
    if (service != null) {
      try {
        service.close();
      } catch (IOException ex) {
        LOG.log(Level.FINE, "Error closing the file watcher", ex);
      }
    }
  }

  private void run() {
    LOG.log(Level.INFO, "Watching {0} for Claude Code edits", root);
    try (WatchService service = java.nio.file.FileSystems.getDefault().newWatchService()) {
      watchService = service;
      int registered = registerTree(root, service);
      LOG.log(
          Level.INFO,
          "Registered {0} director{1} under {2}",
          new Object[] {registered, registered == 1 ? "y" : "ies", root});
      if (registered == 0) {
        LOG.log(
            Level.WARNING,
            "No directories were registered under {0} - edits there will not be "
                + "detected. Check that the path exists and is not a broken symlink.",
            root);
      }
      while (true) {
        WatchKey key = service.take();
        Path directory = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
          if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
            continue;
          }
          @SuppressWarnings("unchecked")
          Path relative = ((WatchEvent<Path>) event).context();
          Path changed = directory.resolve(relative);
          LOG.log(Level.FINE, "Watch event {0} on {1}", new Object[] {event.kind(), changed});
          handleChange(changed, event.kind(), service);
        }
        if (!key.reset()) {
          key.cancel();
        }
      }
    } catch (ClosedWatchServiceException | InterruptedException expected) {
      // Normal shutdown path: stop() closes the service, or the IDE is exiting.
    } catch (IOException ex) {
      LOG.log(Level.WARNING, "Could not watch " + root + " for Claude Code edits", ex);
    }
  }

  private void handleChange(Path changed, WatchEvent.Kind<?> kind, WatchService service) {
    if (Files.isDirectory(changed)) {
      if (kind == StandardWatchEventKinds.ENTRY_CREATE && !isIgnored(changed)) {
        registerTree(changed, service);
      }
      return;
    }
    if (isIgnored(changed) || !Files.isRegularFile(changed)) {
      return;
    }
    if (kind != StandardWatchEventKinds.ENTRY_CREATE
        && kind != StandardWatchEventKinds.ENTRY_MODIFY) {
      return;
    }
    long now = System.currentTimeMillis();
    Long last = lastOpened.get(changed);
    if (last != null && now - last < DEBOUNCE_MILLIS) {
      return;
    }
    lastOpened.put(changed, now);
    openInEditor(changed.toFile());
  }

  /**
   * Ignores by directory/file name relative to the watched root, not the full absolute path (so an
   * unrelated ancestor folder named e.g. "bin" never shadows the whole watch).
   */
  private boolean isIgnored(Path path) {
    Path relative = path.startsWith(root) ? root.relativize(path) : path;
    for (Path segment : relative) {
      String name = segment.toString();
      if (IGNORED_DIR_NAMES.contains(name) || name.startsWith(".")) {
        return true;
      }
    }
    return false;
  }

  private int registerTree(Path start, WatchService service) {
    AtomicInteger count = new AtomicInteger();
    try {
      Files.walkFileTree(
          start,
          EnumSet.of(FileVisitOption.FOLLOW_LINKS),
          Integer.MAX_VALUE,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              if (isIgnored(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              try {
                dir.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
                count.incrementAndGet();
              } catch (IOException ex) {
                LOG.log(Level.WARNING, "Could not watch " + dir, ex);
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException ex) {
      LOG.log(Level.WARNING, "Could not walk " + start + " to register watches", ex);
    }
    return count.get();
  }

  private static void openInEditor(File file) {
    SwingUtilities.invokeLater(
        () -> {
          // The change came from outside NetBeans' own filesystem layer, so its FileObject
          // cache (and that of its parent folder, for files Claude just created) needs an
          // explicit nudge before it will resolve the path at all.
          FileUtil.refreshFor(file.getParentFile());
          FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(file));
          if (fo == null) {
            LOG.log(Level.WARNING, "Could not resolve a FileObject for {0}", file);
            return;
          }
          try {
            DataObject dataObject = DataObject.find(fo);
            OpenCookie open = dataObject.getLookup().lookup(OpenCookie.class);
            if (open != null) {
              open.open();
            } else {
              LOG.log(Level.FINE, "{0} has no OpenCookie", file);
            }
          } catch (DataObjectNotFoundException ex) {
            LOG.log(Level.WARNING, "No DataObject for " + file, ex);
          }
        });
  }
}
