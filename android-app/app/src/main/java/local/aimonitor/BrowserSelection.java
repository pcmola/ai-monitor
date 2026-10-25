package local.aimonitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Only general web browsers may receive public usage-page links. */
public final class BrowserSelection {
  public record Candidate(
      String packageName,
      String activityName,
      String label,
      boolean handlesAllWebUrls,
      boolean enabled,
      boolean exported) {}

  private BrowserSelection() {}

  public static List<Candidate> eligible(List<Candidate> candidates) {
    LinkedHashMap<String, Candidate> unique = new LinkedHashMap<>();
    for (Candidate candidate : candidates) {
      if (candidate == null
          || !candidate.handlesAllWebUrls()
          || !candidate.enabled()
          || !candidate.exported()
          || candidate.packageName() == null
          || candidate.packageName().trim().isEmpty()
          || candidate.activityName() == null
          || candidate.activityName().trim().isEmpty()) continue;
      unique.putIfAbsent(candidate.packageName(), candidate);
    }
    List<Candidate> result = new ArrayList<>(unique.values());
    result.sort(
        Comparator.comparing(BrowserSelection::label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Candidate::packageName));
    return Collections.unmodifiableList(result);
  }

  public static Candidate preferred(List<Candidate> eligible, String defaultPackage) {
    for (Candidate candidate : eligible)
      if (candidate.packageName().equals(defaultPackage)) return candidate;
    return eligible.size() == 1 ? eligible.get(0) : null;
  }

  /** Show a choice rather than silently reopening a failing default browser. */
  public static List<Candidate> choices(List<Candidate> eligible, String defaultPackage) {
    List<Candidate> result = new ArrayList<>(eligible);
    result.sort(
        Comparator.comparingInt(
                (Candidate candidate) ->
                    "com.android.chrome".equals(candidate.packageName())
                        ? 0
                        : candidate.packageName().equals(defaultPackage) ? 1 : 2)
            .thenComparing(BrowserSelection::label, String.CASE_INSENSITIVE_ORDER));
    return Collections.unmodifiableList(result);
  }

  public static String label(Candidate candidate) {
    return candidate.label() == null || candidate.label().trim().isEmpty()
        ? candidate.packageName()
        : candidate.label();
  }
}
