package local.aimonitor;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** All fixtures are synthetic; tests never discover or open local sessions. */
public class CodexUsageParserTest {
  private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
  private static final int MIB = 1024 * 1024;

  private static JSONObject event(Instant time, Long cumulative, Long total, Long output)
      throws JSONException {
    JSONObject payload = new JSONObject().put("type", "token_count");
    if (cumulative != null) {
      JSONObject info =
          new JSONObject()
              .put("total_token_usage", new JSONObject().put("total_tokens", cumulative));
      if (total != null || output != null) {
        JSONObject last = new JSONObject();
        if (total != null) last.put("total_tokens", total);
        if (output != null) last.put("output_tokens", output);
        info.put("last_token_usage", last);
      }
      payload.put("info", info);
    }
    return new JSONObject().put("timestamp", time.toString()).put("payload", payload);
  }

  private static JSONObject turn(Instant time, long cumulative, long total, long output)
      throws JSONException {
    return event(time, cumulative, total, output);
  }

  private static JSONObject window(long minutes, Object percent, Object reset)
      throws JSONException {
    JSONObject result =
        new JSONObject().put("window_minutes", minutes).put("used_percent", percent);
    if (reset != null) result.put("resets_at", reset);
    return result;
  }

  private static JSONObject withLimits(JSONObject row, String plan, double percent, Instant reset)
      throws JSONException {
    row.getJSONObject("payload")
        .put(
            "rate_limits",
            new JSONObject()
                .put("plan_type", plan)
                .put("primary", window(300, percent, reset.getEpochSecond()))
                .put("secondary", window(10080, percent / 2, reset.getEpochSecond())));
    return row;
  }

  private static String lines(JSONObject... rows) {
    StringBuilder text = new StringBuilder();
    for (JSONObject row : rows) text.append(row).append('\n');
    return text.toString();
  }

  private static UsageReport parse(String... files) throws IOException {
    List<Reader> readers = new ArrayList<>();
    for (String file : files) readers.add(new StringReader(file));
    return CodexUsageParser.parse(readers, NOW);
  }

  private static void usage(UsageReport report, int index, long requests, long output, long total) {
    UsageReport.Usage actual = report.usage.get(index);
    assertEquals(index == 0 ? "5h" : "7d", actual.label());
    assertEquals(requests, actual.requests());
    assertEquals(output, actual.outputTokens());
    assertEquals(total, actual.totalTokens());
  }

  @Test
  public void newestSnapshotWinsAcrossFilesAndOutOfOrderLines() throws Exception {
    Instant older = NOW.minusSeconds(120);
    Instant newer = NOW.minusSeconds(60);
    JSONObject old = withLimits(event(older, null, null, null), "old", 10, NOW.plusSeconds(10));
    JSONObject newest =
        withLimits(event(newer, null, null, null), "pro", 73.5, NOW.plusSeconds(3600));
    JSONObject totals = turn(NOW, 100, 100, 40);
    UsageReport report = parse(lines(newest, old), lines(old, totals));
    assertEquals("pro", report.plan);
    assertEquals(newer, report.asOf);
    assertEquals(2, report.windows.size());
    assertEquals("primary", report.windows.get(0).id());
    assertEquals("5h", report.windows.get(0).label());
    assertEquals(73.5, report.windows.get(0).usedPercent(), 0);
    assertEquals("secondary", report.windows.get(1).id());
    assertEquals("7d", report.windows.get(1).label());
    assertEquals(36.75, report.windows.get(1).usedPercent(), 0);
    assertEquals(NOW.plusSeconds(3600), report.windows.get(0).resetsAt());
    usage(report, 0, 1, 40, 100);
  }

  @Test
  public void tiedSnapshotsPreferFirstEncountered() throws Exception {
    JSONObject first = withLimits(event(NOW, null, null, null), "first", 10, NOW);
    JSONObject second = withLimits(event(NOW, null, null, null), "second", 90, NOW);
    assertEquals("first", parse(lines(first), lines(second)).plan);
  }

  @Test
  public void aggregatesLastUsageInsteadOfCumulativeDeltaAndUsesInclusiveCutoffs()
      throws Exception {
    UsageReport report =
        parse(
            lines(
                turn(NOW.minusSeconds(7 * 86400 + 1), 900, 900, 90),
                turn(NOW.minusSeconds(7 * 86400), 1000, 100, 10),
                turn(NOW.minusSeconds(5 * 3600 + 1), 1100, 80, 20),
                turn(NOW.minusSeconds(5 * 3600), 5000, 70, 30),
                turn(NOW, 5100, 60, 40)));
    usage(report, 0, 2, 70, 130);
    usage(report, 1, 4, 100, 310);
  }

  @Test
  public void nanosecondBoundaryAndFutureAreHandledPrecisely() throws Exception {
    UsageReport report =
        parse(
            lines(
                turn(NOW.minusSeconds(18000).minusNanos(1), 1, 1, 1),
                turn(NOW, 2, 2, 1),
                turn(NOW.plusNanos(1), 3, 3, 1)));
    usage(report, 0, 1, 1, 2);
    usage(report, 1, 2, 2, 3);
  }

  @Test
  public void repeatedCumulativeDoesNotCountButCanUpdateLimits() throws Exception {
    JSONObject repeated = withLimits(turn(NOW, 100, 999, 999), "plus", 85, NOW.plusSeconds(60));
    UsageReport report =
        parse(
            lines(
                turn(NOW.minusSeconds(2), 100, 100, 30),
                turn(NOW.minusSeconds(1), 100, 100, 30),
                repeated));
    usage(report, 0, 1, 30, 100);
    assertEquals("plus", report.plan);
    assertEquals(NOW, report.asOf);
  }

  @Test
  public void cumulativeStateIsPerFileAndDecreasesAreChanges() throws Exception {
    UsageReport report =
        parse(
            lines(
                turn(NOW.minusSeconds(3), 100, 10, 1),
                turn(NOW.minusSeconds(2), 10, 5, 2),
                turn(NOW.minusSeconds(1), 100, 20, 3)),
            lines(turn(NOW, 100, 30, 4)));
    usage(report, 0, 4, 10, 65);
  }

  @Test
  public void equalTotalsAcrossSessionsRequireSeparateReadersInSanitizedExports() throws Exception {
    String sessionA =
        lines(turn(NOW.minusSeconds(3), 100, 70, 7), turn(NOW.minusSeconds(2), 100, 70, 7));
    String sessionB = lines(turn(NOW.minusSeconds(1), 100, 20, 2), turn(NOW, 100, 20, 2));

    UsageReport separate = parse(sessionA, sessionB);
    usage(separate, 0, 2, 9, 90);
    usage(separate, 1, 2, 9, 90);

    // Without usage_source, a single Reader cannot recover lost boundaries.
    String concatenated = sessionA + sessionB;
    UsageReport combined = parse(concatenated);
    usage(combined, 0, 1, 7, 70);
    usage(combined, 1, 1, 7, 70);
    // Duplicate-import detection cannot repair the missing session boundary.
    usage(parse(concatenated, concatenated), 0, 1, 7, 70);
  }

  @Test
  public void sessionMetadataAndUnrecognizedOpaqueIdsDoNotResetCumulativeState() throws Exception {
    JSONObject first = turn(NOW.minusSeconds(1), 100, 70, 7);
    first.getJSONObject("payload").put("source_session", "synthetic-opaque-a");
    JSONObject second = turn(NOW, 100, 20, 2);
    second
        .getJSONObject("payload")
        .put("source_session", "synthetic-opaque-b")
        .put("session_id", "synthetic-opaque-b");
    String wrapper =
        new JSONObject()
            .put("type", "session_meta")
            .put("payload", new JSONObject().put("id", "synthetic-opaque-b"))
            .toString();

    UsageReport report = parse(lines(first) + "\n" + wrapper + "\n" + lines(second));
    usage(report, 0, 1, 7, 70);
    usage(report, 1, 1, 7, 70);
  }

  @Test
  public void identicalMetricsFromDistinctSessionIdsStillDeduplicateWithoutIdentitySupport()
      throws Exception {
    JSONObject first = turn(NOW, 100, 70, 7);
    first.getJSONObject("payload").put("source_session", "synthetic-opaque-a");
    JSONObject second = turn(NOW, 100, 70, 7);
    second.getJSONObject("payload").put("source_session", "synthetic-opaque-b");

    // Document the information limit: unknown identity fields are not hashed.
    UsageReport report = parse(lines(first), lines(second));
    usage(report, 0, 1, 7, 70);
    usage(report, 1, 1, 7, 70);
  }

  @Test
  public void usageSourcesTrackEqualAndInterleavedCumulativeTotalsIndependently() throws Exception {
    String sourceA = "a".repeat(64);
    String sourceB = "b".repeat(64);
    String file =
        lines(
            turn(NOW.minusSeconds(5), 100, 70, 7).put("usage_source", sourceA),
            turn(NOW.minusSeconds(4), 100, 20, 2).put("usage_source", sourceB),
            turn(NOW.minusSeconds(3), 100, 70, 7).put("usage_source", sourceA),
            turn(NOW.minusSeconds(2), 200, 30, 3).put("usage_source", sourceB),
            turn(NOW.minusSeconds(1), 200, 40, 4).put("usage_source", sourceA),
            turn(NOW, 200, 30, 3).put("usage_source", sourceB));
    UsageReport report = parse(file, file);
    usage(report, 0, 4, 16, 160);
    usage(report, 1, 4, 16, 160);
  }

  @Test
  public void usageSourceDistinguishesIdenticalMetricsInFingerprintsAndNormalizesCase()
      throws Exception {
    JSONObject first = turn(NOW, 100, 70, 7).put("usage_source", "a".repeat(64));
    JSONObject sameSource = turn(NOW, 100, 70, 7).put("usage_source", "A".repeat(64));
    JSONObject otherSource = turn(NOW, 100, 70, 7).put("usage_source", "b".repeat(64));
    UsageReport separate = parse(lines(first), lines(sameSource), lines(otherSource));
    usage(separate, 0, 2, 14, 140);
    usage(separate, 1, 2, 14, 140);
    usage(parse(lines(first, sameSource, otherSource)), 0, 2, 14, 140);

    // Missing and explicit empty source retain the ordinary raw-log identity.
    JSONObject raw = turn(NOW, 100, 70, 7);
    JSONObject empty = turn(NOW, 100, 70, 7).put("usage_source", "");
    usage(parse(lines(raw), lines(empty)), 0, 1, 7, 70);
    usage(parse(lines(raw, empty, first)), 0, 2, 14, 140);
  }

  @Test
  public void malformedUsageSourcesAreSkippedWithoutRetainingPathsOrOtherContent()
      throws Exception {
    for (Object source :
        new Object[] {
          "synthetic/private/path",
          "a".repeat(63),
          "g".repeat(64),
          "a".repeat(65),
          "\uFF11".repeat(64),
          123,
          JSONObject.NULL
        }) {
      JSONObject invalid = turn(NOW, 100, 70, 7).put("usage_source", source);
      IOException error = assertThrows(IOException.class, () -> parse(lines(invalid)));
      assertFalse(error.toString().contains("synthetic/private/path"));
      assertNull(error.getCause());
      usage(parse(lines(invalid, turn(NOW, 100, 20, 2))), 0, 1, 2, 20);
    }
  }

  @Test
  public void exactDuplicateImportsCountOnce() throws Exception {
    String file = lines(turn(NOW.minusSeconds(1), 100, 100, 10), turn(NOW, 200, 70, 20));
    UsageReport report = parse(file, file, file);
    usage(report, 0, 2, 30, 170);
    usage(report, 1, 2, 30, 170);
  }

  @Test
  public void equivalentUsageSequencesIgnoreFormattingAndConversationContent() throws Exception {
    JSONObject row = turn(NOW, 100, 90, 10);
    String reordered =
        "{\"payload\":"
            + row.getJSONObject("payload")
            + ",\"timestamp\":\""
            + NOW
            + "\",\"conversation\":\"synthetic private text\"}";
    UsageReport report = parse(lines(row), "not json\n" + reordered + "\r\n");
    usage(report, 0, 1, 10, 90);
    assertNull(report.plan);
  }

  @Test
  public void partiallyOverlappingFilesAreNotGloballyEventDeduplicated() throws Exception {
    JSONObject shared = turn(NOW.minusSeconds(1), 100, 10, 1);
    UsageReport report = parse(lines(shared), lines(shared, turn(NOW, 200, 20, 2)));
    usage(report, 0, 3, 4, 40);
  }

  @Test
  public void oldRepeatedTotalDoesNotBecomeANewRequestWhenRepeatedRecently() throws Exception {
    UsageReport report =
        parse(lines(turn(NOW.minusSeconds(8 * 86400), 100, 100, 10), turn(NOW, 100, 100, 10)));
    usage(report, 0, 0, 0, 0);
    usage(report, 1, 0, 0, 0);
  }

  @Test
  public void futureEventsAffectNeitherLimitsNorCumulativeState() throws Exception {
    JSONObject future =
        withLimits(turn(NOW.plusSeconds(60), 200, 900, 90), "future", 99, NOW.plusSeconds(3600));
    JSONObject past =
        withLimits(turn(NOW.minusSeconds(1), 100, 10, 1), "past", 25, NOW.plusSeconds(100));
    UsageReport report = parse(lines(past, future, turn(NOW, 200, 20, 2)));
    assertEquals("past", report.plan);
    usage(report, 0, 2, 3, 30);
  }

  @Test
  public void expiredLimitsKeepUtilizationAndOriginalResetTime() throws Exception {
    Instant reset = NOW.minusSeconds(60);
    UsageReport report =
        parse(lines(withLimits(event(NOW.minusSeconds(120), null, null, null), "plus", 92, reset)));
    assertEquals(92, report.windows.get(0).usedPercent(), 0);
    assertEquals(reset, report.windows.get(0).resetsAt());
    assertEquals(46, report.windows.get(1).usedPercent(), 0);
    usage(report, 0, 0, 0, 0);
  }

  @Test
  public void zeroEpochIsPreservedAsExpiredRatherThanUnknown() throws Exception {
    UsageReport report =
        parse(lines(withLimits(event(NOW, null, null, null), "plus", 65, Instant.EPOCH)));
    assertEquals(Instant.EPOCH, report.windows.get(0).resetsAt());
    assertEquals(65, report.windows.get(0).usedPercent(), 0);
  }

  @Test
  public void totalsOnlyHasEmptyWindowsAndNewestTotalsTimestamp() throws Exception {
    UsageReport report =
        parse(lines(turn(NOW, 100, 50, 15), turn(NOW.minusSeconds(60), 200, 20, 5)));
    assertNull(report.plan);
    assertEquals(NOW, report.asOf);
    assertTrue(report.windows.isEmpty());
    usage(report, 0, 2, 20, 70);
  }

  @Test
  public void missingLastUsageAndNullCountersMatchReferenceDefaults() throws Exception {
    JSONObject missing = event(NOW.minusSeconds(1), 100L, null, null);
    JSONObject nulls = turn(NOW, 200, 1, 0);
    nulls
        .getJSONObject("payload")
        .getJSONObject("info")
        .getJSONObject("last_token_usage")
        .put("total_tokens", JSONObject.NULL)
        .put("output_tokens", JSONObject.NULL);
    UsageReport report = parse(lines(missing, nulls));
    usage(report, 0, 2, 0, 0);
  }

  @Test
  public void ignoresMalformedLinesAndWrongEventTypesWithoutLosingValidRecords() throws Exception {
    JSONObject wrong = turn(NOW, 999, 999, 999);
    wrong.getJSONObject("payload").put("type", "message");
    JSONObject missingTimestamp = turn(NOW, 888, 888, 888);
    missingTimestamp.remove("timestamp");
    JSONObject badTimestamp = turn(NOW, 777, 777, 777).put("timestamp", "private-invalid-time");
    String malformed =
        "{broken\nnull\n[]\n{}\n{\"payload\":null}\n{\"payload\":42}\n"
            + lines(wrong, missingTimestamp, badTimestamp)
            + turn(NOW, 666, 666, 666)
            + " trailing private text\n";
    UsageReport report = parse(malformed + lines(turn(NOW, 100, 30, 7)) + "{unfinished");
    usage(report, 0, 1, 7, 30);
  }

  @Test
  public void newestMalformedLimitsDoNotErasePreviousSnapshotOrValidUsage() throws Exception {
    JSONObject good = withLimits(event(NOW.minusSeconds(60), null, null, null), "plus", 40, NOW);
    JSONObject bad = turn(NOW, 100, 50, 10);
    bad.getJSONObject("payload")
        .put(
            "rate_limits",
            new JSONObject().put("plan_type", "bad").put("primary", window(300, "unknown", 1)));
    UsageReport report = parse(lines(good, bad));
    assertEquals("plus", report.plan);
    assertEquals(NOW.minusSeconds(60), report.asOf);
    usage(report, 0, 1, 10, 50);
  }

  @Test
  public void missingUtilizationDoesNotFabricateZeroAndInvalidWindowDoesNotHideSibling()
      throws Exception {
    JSONObject row = event(NOW, null, null, null);
    JSONObject primary = new JSONObject().put("window_minutes", 300);
    row.getJSONObject("payload")
        .put(
            "rate_limits",
            new JSONObject().put("primary", primary).put("secondary", window(10080, 20, null)));
    UsageReport report = parse(lines(row));
    assertEquals(1, report.windows.size());
    assertEquals("secondary", report.windows.get(0).id());
    assertNull(report.windows.get(0).resetsAt());
    assertNull(report.plan);
  }

  @Test
  public void finiteUtilizationIsClampedAndNonfiniteOrNonnumericIsRejected() throws Exception {
    JSONObject row = event(NOW, null, null, null);
    JSONObject limits =
        new JSONObject()
            .put("primary", window(300, -5, null))
            .put("secondary", window(10080, 125, null));
    row.getJSONObject("payload").put("rate_limits", limits);
    UsageReport report = parse(lines(row));
    assertEquals(0, report.windows.get(0).usedPercent(), 0);
    assertEquals(100, report.windows.get(1).usedPercent(), 0);
    for (String invalid : new String[] {"null", "\"NaN\"", "\"Infinity\"", "1e9999", "true"}) {
      String bad =
          "{\"timestamp\":\""
              + NOW
              + "\",\"payload\":{\"type\":\"token_count\","
              + "\"rate_limits\":{\"primary\":{\"window_minutes\":300,\"used_percent\":"
              + invalid
              + "}}}}";
      assertThrows(IOException.class, () -> parse(bad));
    }
  }

  @Test
  public void invalidResetsAndWindowLengthsAreNotConvertedIntoFreshLimits() throws Exception {
    for (Object reset : new Object[] {"123", -1, 1.5, Long.MAX_VALUE}) {
      JSONObject row = event(NOW, null, null, null);
      row.getJSONObject("payload")
          .put("rate_limits", new JSONObject().put("primary", window(300, 80, reset)));
      assertThrows(IOException.class, () -> parse(lines(row)));
    }
    for (Object minutes : new Object[] {0, -1, 1.5, "300", JSONObject.NULL}) {
      JSONObject row = event(NOW, null, null, null);
      row.getJSONObject("payload")
          .put(
              "rate_limits",
              new JSONObject()
                  .put("primary", window(300, 80, null).put("window_minutes", minutes)));
      assertThrows(IOException.class, () -> parse(lines(row)));
    }
  }

  @Test
  public void arbitraryWindowMinutesGetAccurateLabels() throws Exception {
    JSONObject row = event(NOW, null, null, null);
    row.getJSONObject("payload")
        .put(
            "rate_limits",
            new JSONObject()
                .put("primary", window(90, 20, null))
                .put("secondary", window(120, 30, null)));
    UsageReport report = parse(lines(row));
    assertEquals("90m", report.windows.get(0).label());
    assertEquals("2h", report.windows.get(1).label());
  }

  @Test
  public void malformedTotalsAreSkippedWithoutPoisoningCumulativeState() throws Exception {
    List<JSONObject> rows = new ArrayList<>();
    for (Object value :
        new Object[] {
          -1, 0.5, "10", JSONObject.NULL, new java.math.BigInteger("9223372036854775808")
        }) {
      JSONObject bad = turn(NOW, 100, 10, 1);
      bad.getJSONObject("payload")
          .getJSONObject("info")
          .getJSONObject("total_token_usage")
          .put("total_tokens", value);
      rows.add(bad);
    }
    JSONObject badTurn = turn(NOW, 100, 10, 1);
    badTurn
        .getJSONObject("payload")
        .getJSONObject("info")
        .getJSONObject("last_token_usage")
        .put("total_tokens", -1);
    rows.add(badTurn);
    rows.add(turn(NOW, 100, 2, 3));
    rows.add(turn(NOW, 100, 10, 1));
    UsageReport report = parse(lines(rows.toArray(new JSONObject[0])));
    usage(report, 0, 1, 1, 10);
  }

  @Test
  public void noEventsThrowsFriendlyErrorInsteadOfInventingZeroUsage() throws Exception {
    for (String file :
        new String[] {
          "",
          "\n\r\n",
          "{bad private-content",
          "{}",
          "[]",
          lines(turn(NOW.plusSeconds(1), 100, 10, 1))
        }) {
      IOException error = assertThrows(IOException.class, () -> parse(file));
      assertTrue(error.getMessage().contains("No valid Codex token_count"));
      assertFalse(error.getMessage().contains("private-content"));
      assertNull(error.getCause());
    }
    assertThrows(IOException.class, () -> parse());
  }

  @Test
  public void validTokenEventWithoutLimitsOrTotalsHasSpecificError() {
    IOException error =
        assertThrows(IOException.class, () -> parse(lines(event(NOW, null, null, null))));
    assertTrue(error.getMessage().contains("no usable rate limits or token totals"));
  }

  @Test
  public void emptyAndMalformedFilesDoNotPreventOtherFilesFromReporting() throws Exception {
    UsageReport report = parse("", "bad\n", lines(turn(NOW, 100, 10, 1)));
    usage(report, 0, 1, 1, 10);
  }

  @Test
  public void handlesBomCrLfCrAndFinalLineWithoutNewline() throws Exception {
    String file =
        "\uFEFF"
            + turn(NOW.minusSeconds(2), 100, 10, 1)
            + "\r\n"
            + turn(NOW.minusSeconds(1), 200, 20, 2)
            + "\r"
            + turn(NOW, 300, 30, 3);
    usage(parse(file), 0, 3, 6, 60);
  }

  @Test
  public void exactOneMiBLineIsAcceptedAndLargerLineIsDrained() throws Exception {
    String base = turn(NOW, 100, 10, 1).put("padding", "").toString();
    String exact =
        base.replace("\"padding\":\"\"", "\"padding\":\"" + "x".repeat(MIB - base.length()) + "\"");
    assertEquals(MIB, exact.length());
    usage(parse(exact), 0, 1, 1, 10);
    String oversized = exact.replace("\"padding\":\"", "\"padding\":\"x");
    UsageReport report = parse(oversized + "\n" + turn(NOW, 200, 20, 2));
    usage(report, 0, 1, 2, 20);
    assertThrows(IOException.class, () -> parse(oversized));
  }

  @Test
  public void utf8BudgetCountsMultibyteCharactersAndSurrogatePairs() throws Exception {
    String tooLarge = turn(NOW, 100, 10, 1).put("padding", "가".repeat(MIB / 3)).toString();
    assertTrue(tooLarge.length() < MIB);
    usage(parse(tooLarge + "\n" + turn(NOW, 200, 20, 2)), 0, 1, 2, 20);
    String base = turn(NOW, 100, 10, 1).put("padding", "").toString();
    int pairs = (MIB - base.length()) / 4;
    String emoji = base.replace("\"padding\":\"\"", "\"padding\":\"" + "😀".repeat(pairs) + "\"");
    usage(parse(emoji), 0, 1, 1, 10);
  }

  @Test
  public void deeplyNestedLineIsSkippedAndBracketsInsideStringsDoNotCount() throws Exception {
    String deep = "{\"extra\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}";
    JSONObject valid =
        turn(NOW, 100, 10, 1).put("text", "[".repeat(100) + "\\\"quoted\\\"" + "]".repeat(100));
    usage(parse(deep + "\n" + valid), 0, 1, 1, 10);
  }

  @Test
  public void readersRemainOpenOnSuccessAndValidationFailure() throws Exception {
    TrackingReader good = new TrackingReader(lines(turn(NOW, 100, 10, 1)));
    CodexUsageParser.parse(List.of(good), NOW);
    assertFalse(good.closed);
    TrackingReader empty = new TrackingReader("");
    assertThrows(IOException.class, () -> CodexUsageParser.parse(List.of(empty), NOW));
    assertFalse(empty.closed);
  }

  @Test
  public void alreadyCancelledImportPreservesInterruptAndReaderOwnership() {
    TrackingReader reader = new TrackingReader("synthetic-content");
    Thread.currentThread().interrupt();
    try {
      InterruptedIOException error =
          assertThrows(
              InterruptedIOException.class, () -> CodexUsageParser.parse(List.of(reader), NOW));
      assertTrue(error.getMessage().contains("cancelled"));
      assertTrue(Thread.currentThread().isInterrupted());
      assertFalse(reader.closed);
      assertNull(error.getCause());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void cancellationWhileDrainingOversizedLineStopsBeforeReadingRemainder() {
    int[] delivered = {0};
    Reader reader =
        new Reader() {
          @Override
          public int read(char[] buffer, int offset, int length) {
            if (delivered[0] >= MIB * 2) {
              fail("cancelled oversized input must stop promptly");
            }
            java.util.Arrays.fill(buffer, offset, offset + length, 'x');
            delivered[0] += length;
            if (delivered[0] > MIB + 8192) Thread.currentThread().interrupt();
            return length;
          }

          @Override
          public void close() {
            fail("caller owns the reader");
          }
        };
    try {
      assertThrows(
          InterruptedIOException.class, () -> CodexUsageParser.parse(List.of(reader), NOW));
      assertTrue(delivered[0] > MIB);
      assertTrue(delivered[0] <= MIB + 24576);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void readFailureDoesNotReturnPartialReportOrLeakUnderlyingError() {
    Reader broken =
        new Reader() {
          @Override
          public int read(char[] buffer, int offset, int length) throws IOException {
            throw new IOException("private-path private-content private-token");
          }

          @Override
          public void close() {
            fail("caller owns the reader");
          }
        };
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                CodexUsageParser.parse(
                    List.of(new StringReader(lines(turn(NOW, 100, 10, 1))), broken), NOW));
    assertTrue(error.getMessage().contains("Unable to read Codex usage logs"));
    assertFalse(error.toString().contains("private"));
    assertNull(error.getCause());
  }

  @Test
  public void sumsDoNotSilentlyWrapOnOverflowWithinOrAcrossFiles() throws Exception {
    JSONObject huge = turn(NOW.minusSeconds(1), Long.MAX_VALUE, Long.MAX_VALUE, 0);
    JSONObject extra = turn(NOW, 1, 1, 0);
    IOException within = assertThrows(IOException.class, () -> parse(lines(huge, extra)));
    assertTrue(within.getMessage().contains("too large"));
    IOException error = assertThrows(IOException.class, () -> parse(lines(huge), lines(extra)));
    assertTrue(error.getMessage().contains("too large"));
    assertNull(error.getCause());
  }

  @Test
  public void reportDefensivelyCopiesListsAndExposesImmutableRecords() {
    List<UsageReport.Window> windows = new ArrayList<>();
    List<UsageReport.Usage> usages = new ArrayList<>();
    UsageReport.Window window = new UsageReport.Window("primary", "5h", 25, NOW);
    UsageReport.Usage usage = new UsageReport.Usage("5h", 1, 2, 3);
    windows.add(window);
    usages.add(usage);
    UsageReport report = new UsageReport("plus", NOW, windows, usages);
    windows.clear();
    usages.clear();
    assertEquals(List.of(window), report.windows);
    assertEquals(List.of(usage), report.usage);
    assertThrows(UnsupportedOperationException.class, report.windows::clear);
    assertThrows(UnsupportedOperationException.class, () -> report.usage.add(usage));
    assertEquals(new UsageReport.Window("primary", "5h", 25, NOW), window);
    assertEquals(new UsageReport.Usage("5h", 1, 2, 3), usage);
  }

  private static final class TrackingReader extends StringReader {
    boolean closed;

    TrackingReader(String text) {
      super(text);
    }

    @Override
    public void close() {
      closed = true;
      super.close();
    }
  }
}
