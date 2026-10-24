package local.aimonitor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persists quota/count summaries only; never conversation lines. */
final class UsageCodec {
  private UsageCodec() {}

  static JSONObject encode(UsageReport report) throws Exception {
    JSONArray windows = new JSONArray(), usage = new JSONArray();
    for (UsageReport.Window window : report.windows) {
      windows.put(
          new JSONObject()
              .put("id", window.id())
              .put("label", window.label())
              .put("used", window.usedPercent())
              .put(
                  "reset",
                  window.resetsAt() == null ? JSONObject.NULL : window.resetsAt().toString()));
    }
    for (UsageReport.Usage row : report.usage) {
      usage.put(
          new JSONObject()
              .put("label", row.label())
              .put("requests", row.requests())
              .put("output", row.outputTokens())
              .put("total", row.totalTokens()));
    }
    return new JSONObject()
        .put("plan", report.plan == null ? JSONObject.NULL : report.plan)
        .put("asOf", report.asOf.toString())
        .put("windows", windows)
        .put("usage", usage);
  }

  static UsageReport decode(JSONObject data) throws Exception {
    List<UsageReport.Window> windows = new ArrayList<>();
    List<UsageReport.Usage> usage = new ArrayList<>();
    JSONArray array = data.getJSONArray("windows");
    for (int i = 0; i < array.length(); i++) {
      JSONObject window = array.getJSONObject(i);
      double used = window.getDouble("used");
      if (!Double.isFinite(used) || used < 0 || used > 100)
        throw new java.io.IOException("사용률 형식 오류");
      windows.add(
          new UsageReport.Window(
              window.getString("id"),
              window.getString("label"),
              used,
              window.isNull("reset") ? null : Instant.parse(window.getString("reset"))));
    }
    array = data.getJSONArray("usage");
    for (int i = 0; i < array.length(); i++) {
      JSONObject row = array.getJSONObject(i);
      usage.add(
          new UsageReport.Usage(
              row.getString("label"),
              row.getLong("requests"),
              row.getLong("output"),
              row.getLong("total")));
    }
    return new UsageReport(
        data.isNull("plan") ? null : data.getString("plan"),
        Instant.parse(data.getString("asOf")),
        windows,
        usage);
  }
}
