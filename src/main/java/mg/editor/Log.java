package mg.editor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A process-wide ring buffer of the last {@value #MAX} actions and exceptions,
 * so the editor can show a running overview of what happened and why something
 * failed (see the View ▸ Log window). Thread-safe: generation runs on background
 * threads and logs from there. Listeners are notified after each append so an
 * open log window can refresh.
 */
public final class Log {
  public enum Level { INFO, WARN, ERROR }

  /** one log line: a monotonic sequence number, time, level, message, optional detail. */
  public record Entry(long seq, String time, Level level, String message, String detail) {
    @Override
    public String toString() {
      return time + "  " + level + "  " + message;
    }
  }

  private static final int MAX = 1000;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

  private static final Deque<Entry> entries = new ArrayDeque<>();
  private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  private static long seq;

  private Log() {}

  public static void info(String message) {
    add(Level.INFO, message, null);
  }

  public static void warn(String message) {
    add(Level.WARN, message, null);
  }

  public static void error(String message, Throwable t) {
    add(Level.ERROR, message, t == null ? null : stack(t));
  }

  private static synchronized void add(Level level, String message, String detail) {
    Entry e = new Entry(++seq, LocalTime.now().format(TIME), level, message == null ? "" : message, detail);
    entries.addLast(e);
    while (entries.size() > MAX) {
      entries.removeFirst();
    }
    for (Runnable r : listeners) {
      r.run();
    }
  }

  /** most-recent-last snapshot of the buffer. */
  public static synchronized List<Entry> snapshot() {
    return new ArrayList<>(entries);
  }

  public static synchronized void clear() {
    entries.clear();
    for (Runnable r : listeners) {
      r.run();
    }
  }

  public static void addListener(Runnable r) {
    listeners.add(r);
  }

  public static void removeListener(Runnable r) {
    listeners.remove(r);
  }

  private static String stack(Throwable t) {
    StringWriter sw = new StringWriter();
    t.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }
}
