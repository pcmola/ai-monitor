package local.aimonitor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Parses imported JSONL streams without accessing files, Android, or authentication. Readers remain
 * open and belong to the caller. File count and total input size must be bounded by the caller;
 * individual lines have a 1 MiB UTF-8 budget and a JSON nesting limit of 64. Oversized/malformed
 * lines are discarded through their end.
 *
 * <p>Each file tracks the preceding cumulative total per usage_source in input order: only a
 * changed total contributes last_token_usage (never a cumulative delta). Without usage_source, this
 * matches the PC monitor's per-file tracking. Decreasing totals start another request too. Future
 * events are ignored entirely, including their cumulative totals. The 5h/7d cutoffs are inclusive.
 *
 * <p>Sanitized exporters may concatenate sessions by setting each event's top-level usage_source to
 * a stable, opaque 64-character ASCII hexadecimal SHA-256 string. Sources are normalized to
 * lowercase; absent/empty sources share the default empty identity for ordinary raw logs. A
 * malformed source rejects that line. Sources are used only for tracking/fingerprinting, never
 * exposed in reports. Unannotated concatenation still loses boundaries: if session A ends at
 * cumulative 100 and session B starts at 100, B's first usage is skipped. Such streams must instead
 * be supplied as separate Readers or annotated with distinct sources. Blank lines, session_meta
 * wrappers, and optional source_session/session_id fields are ignored; they do not reset cumulative
 * state or identify a session. Do not insert synthetic token_count reset events: they would count
 * as requests and could corrupt usage/limits. The caller bounds the number of sources/input size.
 * Cancellation checks preserve the thread's interrupted flag; Readers remain caller-owned on
 * cancellation too.
 *
 * <p>Duplicate imports are detected by SHA-256 of the ordered, length-prefixed, normalized
 * token-event fields used here. Only digests and usage summaries survive a file; conversation
 * fields are never retained or hashed. Whitespace, key order and unrelated lines do not affect
 * deduplication. This deduplicates whole event sequences, not individual events across different
 * files: partial overlaps remain separate. With Readers alone, distinct files containing exactly
 * the same relevant events and usage_source cannot be distinguished and are counted once. Source
 * identities are included in the event fingerprints.
 */
public final class CodexUsageParser {
  private static final int MAX_LINE_BYTES = 1024 * 1024;
  private static final Duration FIVE_HOURS = Duration.ofHours(5);
  private static final Duration SEVEN_DAYS = Duration.ofDays(7);

  /**
   * Returns the newest usable rate-limit snapshot and summed local usage. asOf is that snapshot's
   * timestamp, or the newest totals timestamp if there are no usable limits. plan and window
   * resetsAt may be null. Usage labels are "5h" and "7d"; limit ids are "primary" and "secondary".
   * Expired snapshots retain both their utilization and original reset time. Tied snapshot
   * timestamps prefer the first encountered usable snapshot. Concatenated sessions must have
   * distinct top-level usage_source hashes; unannotated sessions must be supplied as separate
   * Readers.
   *
   * @throws IOException if no usable usage exists, reading fails, sums overflow, or the thread is
   *     interrupted (InterruptedIOException); messages never include raw input or underlying errors
   */
  public static UsageReport parse(List<Reader> readers, Instant now) throws IOException {
    Objects.requireNonNull(readers, "readers");
    Objects.requireNonNull(now, "now");
    checkInterrupted();
    Set<String> files = new HashSet<>();
    Totals fiveHours = new Totals();
    Totals sevenDays = new Totals();
    Snapshot latest = null;
    Instant latestTotals = null;
    boolean sawTokenCount = false;

    for (Reader reader : readers) {
      checkInterrupted();
      Objects.requireNonNull(reader, "reader");
      FileResult file = readFile(reader, now);
      sawTokenCount |= file.sawTokenCount;
      if (!files.add(file.fingerprint)) continue;
      fiveHours.merge(file.fiveHours);
      sevenDays.merge(file.sevenDays);
      if (file.latest != null
          && (latest == null || file.latest.timestamp.isAfter(latest.timestamp)))
        latest = file.latest;
      if (file.latestTotals != null
          && (latestTotals == null || file.latestTotals.isAfter(latestTotals)))
        latestTotals = file.latestTotals;
    }

    checkInterrupted();
    if (latest == null && latestTotals == null) {
      throw new IOException(
          sawTokenCount
              ? "Codex token_count logs have no usable rate limits or token totals. Import usage"
                  + " logs with those fields."
              : "No valid Codex token_count events found. Import Codex JSONL usage logs.");
    }
    return new UsageReport(
        latest == null ? null : latest.plan,
        latest == null ? latestTotals : latest.timestamp,
        latest == null ? List.of() : latest.windows,
        List.of(fiveHours.usage("5h"), sevenDays.usage("7d")));
  }

  private static FileResult readFile(Reader reader, Instant now) throws IOException {
    MessageDigest digest = sha256();
    BoundedLines lines = new BoundedLines(reader);
    FileResult result = new FileResult();
    Map<String, Long> previousTotals = new HashMap<>();
    while (true) {
      checkInterrupted();
      String line = lines.next();
      checkInterrupted();
      if (line == null) break;
      Event event = event(line, now);
      if (event == null) continue;
      result.sawTokenCount = true;
      fingerprint(digest, event);
      if (event.snapshot != null
          && (result.latest == null || event.timestamp.isAfter(result.latest.timestamp))) {
        result.latest = event.snapshot;
      }
      if (event.total == null) continue;
      if (result.latestTotals == null || event.timestamp.isAfter(result.latestTotals)) {
        result.latestTotals = event.timestamp;
      }
      if (event.cumulative.equals(previousTotals.get(event.source))) continue;
      previousTotals.put(event.source, event.cumulative);
      Duration age = Duration.between(event.timestamp, now);
      if (age.compareTo(FIVE_HOURS) <= 0) result.fiveHours.add(event.output, event.total);
      if (age.compareTo(SEVEN_DAYS) <= 0) result.sevenDays.add(event.output, event.total);
    }
    result.fingerprint = Base64.getEncoder().encodeToString(digest.digest());
    return result;
  }

  private static Event event(String line, Instant now) {
    if (line.trim().isEmpty() || !boundedNesting(line)) return null;
    try {
      JSONTokener tokens = new JSONTokener(line);
      JSONObject row = new JSONObject(tokens);
      if (tokens.nextClean() != 0) return null;
      JSONObject payload = row.optJSONObject("payload");
      if (payload == null || !"token_count".equals(payload.opt("type"))) return null;
      Object timestamp = row.opt("timestamp");
      if (!(timestamp instanceof String)) return null;
      Instant time = Instant.parse((String) timestamp);
      if (time.isAfter(now)) return null;
      String source = usageSource(row);
      if (source == null) return null;

      Snapshot snapshot = snapshot(payload.optJSONObject("rate_limits"), time);
      JSONObject info = payload.optJSONObject("info");
      JSONObject cumulativeUsage = info == null ? null : info.optJSONObject("total_token_usage");
      Long cumulative =
          cumulativeUsage == null ? null : nonnegativeLong(cumulativeUsage.opt("total_tokens"));
      Long total = null;
      Long output = null;
      if (cumulative != null) {
        Object last = info.opt("last_token_usage");
        if (last == null || last == JSONObject.NULL) {
          total = 0L;
          output = 0L;
        } else if (last instanceof JSONObject) {
          JSONObject turn = (JSONObject) last;
          total = counter(turn, "total_tokens");
          output = counter(turn, "output_tokens");
          if (total == null || output == null || output > total) {
            total = null;
            output = null;
          }
        }
      }
      return new Event(source, time, cumulative, output, total, snapshot);
    } catch (JSONException | DateTimeException malformed) {
      return null;
    }
  }

  private static String usageSource(JSONObject row) {
    Object value = row.opt("usage_source");
    if (value == null) return "";
    if (!(value instanceof String)) return null;
    String source = (String) value;
    if (source.isEmpty()) return "";
    if (source.length() != 64) return null;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))
        return null;
    }
    return source.toLowerCase(Locale.ROOT);
  }

  private static void checkInterrupted() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException("Codex usage import was cancelled.");
    }
  }

  private static Snapshot snapshot(JSONObject limits, Instant time) {
    if (limits == null) return null;
    List<UsageReport.Window> windows = new ArrayList<>();
    for (String id : new String[] {"primary", "secondary"}) {
      JSONObject window = limits.optJSONObject(id);
      if (window == null) continue;
      Long minutes = nonnegativeLong(window.opt("window_minutes"));
      Object used = window.opt("used_percent");
      if (minutes == null || minutes == 0 || !(used instanceof Number)) continue;
      double percent = ((Number) used).doubleValue();
      if (!Double.isFinite(percent)) continue;
      Instant resetsAt = null;
      Object reset = window.opt("resets_at");
      if (reset != null && reset != JSONObject.NULL) {
        Long seconds = nonnegativeLong(reset);
        if (seconds == null) continue;
        try {
          resetsAt = Instant.ofEpochSecond(seconds);
        } catch (DateTimeException invalid) {
          continue;
        }
      }
      String label =
          minutes == 10080 ? "7d" : minutes % 60 == 0 ? (minutes / 60) + "h" : minutes + "m";
      windows.add(new UsageReport.Window(id, label, Math.max(0, Math.min(100, percent)), resetsAt));
    }
    Object plan = limits.opt("plan_type");
    return windows.isEmpty()
        ? null
        : new Snapshot(time, plan instanceof String ? (String) plan : null, windows);
  }

  private static Long counter(JSONObject object, String key) {
    Object value = object.opt(key);
    if (value == null || value == JSONObject.NULL) return 0L;
    return nonnegativeLong(value);
  }

  private static Long nonnegativeLong(Object value) {
    if (!(value instanceof Number)) return null;
    try {
      long number = new BigDecimal(value.toString()).longValueExact();
      return number >= 0 ? number : null;
    } catch (ArithmeticException | NumberFormatException invalid) {
      return null;
    }
  }

  // Reject pathological nesting before handing the bounded line to org.json.
  private static boolean boundedNesting(String line) {
    boolean quoted = false;
    boolean escaped = false;
    int depth = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') quoted = false;
      } else if (c == '"') quoted = true;
      else if (c == '{' || c == '[') {
        if (++depth > 64) return false;
      } else if (c == '}' || c == ']') {
        if (--depth < 0) return false;
      }
    }
    return !quoted && depth == 0;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException unavailable) {
      throw new AssertionError("SHA-256 is required by Java", unavailable);
    }
  }

  private static void fingerprint(MessageDigest digest, Event event) {
    hash(digest, event.source);
    hash(digest, event.timestamp);
    hash(digest, event.cumulative);
    hash(digest, event.output);
    hash(digest, event.total);
    Snapshot snapshot = event.snapshot;
    hash(digest, snapshot == null ? null : snapshot.windows.size());
    if (snapshot == null) return;
    hash(digest, snapshot.plan);
    for (UsageReport.Window window : snapshot.windows) {
      hash(digest, window.id());
      hash(digest, window.usedPercent());
      hash(digest, window.resetsAt());
      hash(digest, window.label());
    }
  }

  private static void hash(MessageDigest digest, Object value) {
    byte[] bytes = value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
    int length = bytes == null ? -1 : bytes.length;
    digest.update((byte) (length >>> 24));
    digest.update((byte) (length >>> 16));
    digest.update((byte) (length >>> 8));
    digest.update((byte) length);
    if (bytes != null) digest.update(bytes);
  }

  private record Event(
      String source,
      Instant timestamp,
      Long cumulative,
      Long output,
      Long total,
      Snapshot snapshot) {}

  private record Snapshot(Instant timestamp, String plan, List<UsageReport.Window> windows) {}

  private static final class FileResult {
    final Totals fiveHours = new Totals();
    final Totals sevenDays = new Totals();
    boolean sawTokenCount;
    String fingerprint;
    Snapshot latest;
    Instant latestTotals;
  }

  private static final class Totals {
    long requests;
    long output;
    long total;

    void add(long outputTokens, long totalTokens) throws IOException {
      merge(1, outputTokens, totalTokens);
    }

    void merge(Totals other) throws IOException {
      merge(other.requests, other.output, other.total);
    }

    private void merge(long count, long outputTokens, long totalTokens) throws IOException {
      try {
        requests = Math.addExact(requests, count);
        output = Math.addExact(output, outputTokens);
        total = Math.addExact(total, totalTokens);
      } catch (ArithmeticException overflow) {
        throw new IOException("Codex usage totals are too large to report.");
      }
    }

    UsageReport.Usage usage(String label) {
      return new UsageReport.Usage(label, requests, output, total);
    }
  }

  /** Streaming line reader: never constructs an unbounded String/readLine result. */
  private static final class BoundedLines {
    private final Reader reader;
    private boolean firstCharacter = true;

    BoundedLines(Reader reader) {
      this.reader = reader instanceof BufferedReader ? reader : new BufferedReader(reader);
    }

    String next() throws IOException {
      StringBuilder line = new StringBuilder();
      int bytes = 0;
      boolean oversized = false;
      boolean highSurrogate = false;
      int characters = 0;
      while (true) {
        // Includes blank lines and draining oversized lines, with no
        // interrupt-flag clearing and at most 1024 characters per check.
        if ((characters++ & 1023) == 0) checkInterrupted();
        int value;
        try {
          value = reader.read();
        } catch (IOException unreadable) {
          checkInterrupted();
          // Reader exception messages/causes may contain paths or raw content.
          throw new IOException(
              "Unable to read Codex usage logs. Check the selected files and try again.");
        }
        if (firstCharacter) {
          firstCharacter = false;
          if (value == '\uFEFF') continue;
        }
        if (value == -1) return oversized || line.length() == 0 ? null : line.toString();
        char c = (char) value;
        if (c == '\n' || c == '\r') {
          if (!oversized && line.length() > 0) return line.toString();
          line.setLength(0);
          bytes = 0;
          oversized = false;
          highSurrogate = false;
          continue;
        }
        if (oversized) {
          continue;
        }
        // A surrogate pair uses four UTF-8 bytes; isolated surrogates
        // conservatively consume three bytes of the same budget.
        bytes +=
            c <= 0x7f ? 1 : c <= 0x7ff ? 2 : Character.isLowSurrogate(c) && highSurrogate ? 1 : 3;
        highSurrogate = Character.isHighSurrogate(c);
        if (bytes > MAX_LINE_BYTES) {
          oversized = true;
          line.setLength(0);
        } else line.append(c);
      }
    }
  }
}
