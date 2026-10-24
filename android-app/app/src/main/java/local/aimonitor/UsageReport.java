package local.aimonitor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** An immutable snapshot of provider quota or local usage; no monetary costs. */
public final class UsageReport {
  public final String plan;
  public final Instant asOf;
  public final List<Window> windows;
  public final List<Usage> usage;

  public record Window(String id, String label, double usedPercent, Instant resetsAt) {}

  public record Usage(String label, long requests, long outputTokens, long totalTokens) {}

  public UsageReport(String plan, Instant asOf, List<Window> windows, List<Usage> usage) {
    this.plan = plan;
    this.asOf = Objects.requireNonNull(asOf, "asOf");
    this.windows = immutableCopy(windows);
    this.usage = immutableCopy(usage);
  }

  private static <T> List<T> immutableCopy(List<T> source) {
    List<T> copy = new ArrayList<>(Objects.requireNonNull(source, "list"));
    for (T item : copy) Objects.requireNonNull(item, "list element");
    return Collections.unmodifiableList(copy);
  }
}
