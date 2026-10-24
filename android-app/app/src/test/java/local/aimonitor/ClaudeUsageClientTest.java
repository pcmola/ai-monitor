package local.aimonitor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

public class ClaudeUsageClientTest {
  private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
  private static final String TOKEN = "fake-oauth-token";
  private static final String REFRESH = "fake-refresh-value";
  private static final int MAX_BYTES = 1024 * 1024;

  private static ClaudeUsageClient.Credential credential() {
    return new ClaudeUsageClient.Credential(TOKEN, 0, "max");
  }

  private static JSONObject json(String text) throws Exception {
    return new JSONObject(text);
  }

  private static String fullCredential(String extra) {
    return "{\"claudeAiOauth\":{\"accessToken\":\""
        + TOKEN
        + "\",\"refreshToken\":\""
        + REFRESH
        + "\""
        + extra
        + "}}";
  }

  private static void assertSanitized(Throwable error, String... secrets) {
    StringWriter output = new StringWriter();
    error.printStackTrace(new PrintWriter(output));
    for (String secret : secrets) assertFalse(output.toString().contains(secret));
    assertNull(error.getCause());
    assertEquals(0, error.getSuppressed().length);
  }

  @Test
  public void parsesFullCredentialsWithoutRetainingRefreshOrRawText() throws Exception {
    long expiry = NOW.plusSeconds(600).toEpochMilli();
    String source = fullCredential(",\"expiresAt\":" + expiry + ",\"subscriptionType\":\"max\"");
    ClaudeUsageClient.Credential result = ClaudeUsageClient.parseCredential(source, NOW);
    assertEquals(TOKEN, result.accessToken());
    assertEquals(expiry, result.expiresAtMillis());
    assertEquals("max", result.plan());
    assertEquals(3, result.getClass().getRecordComponents().length);
    assertEquals(
        Arrays.asList("accessToken", "expiresAtMillis", "plan"),
        Arrays.stream(result.getClass().getRecordComponents()).map(c -> c.getName()).toList());
    assertFalse(result.toString().contains(TOKEN));
    assertFalse(result.toString().contains(REFRESH));
    assertFalse(result.toString().contains(source));
  }

  @Test
  public void parsesBareTokenAndAllowsUnknownExpiry() throws Exception {
    ClaudeUsageClient.Credential result =
        ClaudeUsageClient.parseCredential(" \n" + TOKEN + "\t ", NOW);
    assertEquals(TOKEN, result.accessToken());
    assertEquals(0, result.expiresAtMillis());
    assertNull(result.plan());
    ClaudeUsageClient.Credential fromJson =
        ClaudeUsageClient.parseCredential(fullCredential(""), NOW);
    assertEquals(0, fromJson.expiresAtMillis());
    assertNull(fromJson.plan());
    UsageReport report =
        new ClaudeUsageClient((url, headers) -> json("{\"five_hour\":{\"utilization\":25}}"))
            .fetch(fromJson, NOW);
    assertNull(report.plan);
  }

  @Test
  public void rejectsEmptyApiAdminAndUnsafeTokensWithoutDisclosingInput() {
    for (String text :
        new String[] {
          "",
          " \t\r\n",
          "sk-ant-api-fake",
          "sk-ant-admin-fake",
          "fake token",
          "fake\r\nheader",
          "fake\u0000value",
          "fake\u2003value",
          "[fake]",
          "\"fake\""
        }) {
      IOException error =
          assertThrows(IOException.class, () -> ClaudeUsageClient.parseCredential(text, NOW));
      if (!text.trim().isEmpty()) assertSanitized(error, text);
    }
    assertThrows(IOException.class, () -> ClaudeUsageClient.parseCredential(null, NOW));
    for (String token : new String[] {"sk-ant-api-fake", "sk-ant-admin-fake", "fake token", ""}) {
      assertThrows(
          IOException.class,
          () ->
              ClaudeUsageClient.parseCredential(
                  "{\"claudeAiOauth\":{\"accessToken\":\"" + token + "\"}}", NOW));
    }
  }

  @Test
  public void rejectsMalformedJsonAndWrongCredentialSchemaWithSafeErrors() {
    for (String text :
        new String[] {
          "{broken-" + REFRESH,
          "{}",
          "{\"claudeAiOauth\":null}",
          "{\"claudeAiOauth\":[]}",
          "{\"claudeAiOauth\":{}}",
          "{\"claudeAiOauth\":{\"accessToken\":12}}",
          fullCredential("") + " trailing",
          fullCredential(",\"expiresAt\":null"),
          fullCredential(",\"expiresAt\":\"123\""),
          fullCredential(",\"expiresAt\":1.5"),
          fullCredential(",\"expiresAt\":9223372036854775808"),
          fullCredential(",\"subscriptionType\":{}")
        }) {
      IOException error =
          assertThrows(IOException.class, () -> ClaudeUsageClient.parseCredential(text, NOW));
      assertSanitized(error, TOKEN, REFRESH, text);
    }
  }

  @Test
  public void credentialInputBoundCountsUtf8BytesAndIncludesSurroundingWhitespace()
      throws Exception {
    assertEquals(
        TOKEN,
        ClaudeUsageClient.parseCredential(" ".repeat(MAX_BYTES - TOKEN.length()) + TOKEN, NOW)
            .accessToken());
    for (String text :
        new String[] {
          " ".repeat(MAX_BYTES) + TOKEN,
          fullCredential(",\"ignored\":\"" + "한".repeat(MAX_BYTES / 3) + "\"")
        }) {
      IOException error =
          assertThrows(IOException.class, () -> ClaudeUsageClient.parseCredential(text, NOW));
      assertSanitized(error, TOKEN, REFRESH);
    }
  }

  @Test
  public void expiryAtOrBeforeNowIsRejectedAtImportAndBeforeTransport() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ClaudeUsageClient client =
        new ClaudeUsageClient(
            (url, headers) -> {
              calls.incrementAndGet();
              return json("{\"five_hour\":{\"utilization\":1}}");
            });
    for (long expiry : new long[] {0, -1, NOW.minusMillis(1).toEpochMilli(), NOW.toEpochMilli()}) {
      IOException error =
          assertThrows(
              IOException.class,
              () ->
                  ClaudeUsageClient.parseCredential(
                      fullCredential(",\"expiresAt\":" + expiry), NOW));
      assertTrue(error.getMessage().contains("Reimport"));
      assertSanitized(error, TOKEN, REFRESH);
      if (expiry != 0) {
        assertThrows(
            IOException.class,
            () -> client.fetch(new ClaudeUsageClient.Credential(TOKEN, expiry, "max"), NOW));
      }
    }
    ClaudeUsageClient.Credential imported =
        ClaudeUsageClient.parseCredential(
            fullCredential(",\"expiresAt\":" + NOW.plusMillis(1).toEpochMilli()), NOW);
    assertThrows(IOException.class, () -> client.fetch(imported, NOW.plusMillis(1)));
    assertEquals(0, calls.get());
    client.fetch(imported, NOW);
    assertEquals(1, calls.get());
  }

  @Test
  public void invalidDirectCredentialsCannotSendRequests() {
    ClaudeUsageClient client =
        new ClaudeUsageClient(
            (url, headers) -> {
              fail("Transport must not run for invalid credentials");
              return null;
            });
    assertThrows(IOException.class, () -> client.fetch(null, NOW));
    assertThrows(IOException.class, () -> client.fetch(credential(), null));
    for (String token :
        new String[] {
          null,
          "",
          " ",
          "fake\nvalue",
          "sk-ant-api-fake",
          "sk-ant-admin-fake",
          "x".repeat(MAX_BYTES + 1)
        }) {
      assertThrows(
          IOException.class,
          () -> client.fetch(new ClaudeUsageClient.Credential(token, 0, null), NOW));
    }
  }

  @Test
  public void usesFixedUsageUrlAndOAuthHeadersWithTokenOnlyInHeader() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    ClaudeUsageClient client =
        new ClaudeUsageClient(
            (url, headers) -> {
              calls.incrementAndGet();
              assertEquals("https://api.anthropic.com/api/oauth/usage", url);
              assertFalse(url.contains(TOKEN));
              assertFalse(url.contains("?"));
              assertEquals("Bearer " + TOKEN, headers.get("Authorization"));
              assertEquals("oauth-2025-04-20", headers.get("anthropic-beta"));
              assertEquals("application/json", headers.get("Accept"));
              assertFalse(headers.containsKey("x-api-key"));
              assertFalse(headers.toString().contains(REFRESH));
              assertEquals(3, headers.size());
              return json("{\"five_hour\":{\"utilization\":12.5}}");
            });
    UsageReport report = client.fetch(credential(), NOW);
    assertEquals(1, calls.get());
    assertEquals("max", report.plan);
    assertEquals(NOW, report.asOf);
    assertTrue(report.usage.isEmpty());
    assertEquals(12.5, report.windows.get(0).usedPercent(), 0);
  }

  @Test
  public void parsesAllKnownWindowsInStableOrderAndIgnoresUnrelatedFields() throws Exception {
    UsageReport report =
        ClaudeUsageClient.parsePayload(
            json(
                """
                {"seven_day_sonnet":{"utilization":100},
                 "seven_day":{"utilization":50,"resets_at":"2026-10-31T03:00:00+00:00"},
                 "seven_day_oauth_apps":{"utilization":0.5,"resets_at":null},
                 "five_hour":{"utilization":0,"resets_at":"2026-10-24T08:00:00Z"},
                 "seven_day_opus":{"utilization":75.25},
                 "extra_usage":{"currency":"KRW","utilization":999},"plan":"ignored"}
                """),
            credential(),
            NOW);
    assertEquals(
        Arrays.asList(
            "five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet", "seven_day_oauth_apps"),
        report.windows.stream().map(w -> w.id()).toList());
    assertEquals(0, report.windows.get(0).usedPercent(), 0);
    assertEquals(100, report.windows.get(3).usedPercent(), 0);
    assertEquals(Instant.parse("2026-10-24T08:00:00Z"), report.windows.get(0).resetsAt());
    assertEquals(Instant.parse("2026-10-31T03:00:00Z"), report.windows.get(1).resetsAt());
    assertNull(report.windows.get(2).resetsAt());
    assertNull(report.windows.get(4).resetsAt());
    assertEquals("max", report.plan);
    assertEquals(NOW, report.asOf);
    assertTrue(report.usage.isEmpty());
  }

  @Test
  public void absentAndNullWindowsAreAllowedIfOneValidWindowExists() throws Exception {
    UsageReport report =
        ClaudeUsageClient.parsePayload(
            json(
                """
                {"five_hour":null,"seven_day":{"utilization":23.4},"seven_day_opus":null}
                """),
            credential(),
            NOW);
    assertEquals(1, report.windows.size());
    assertEquals("seven_day", report.windows.get(0).id());
    assertNull(report.windows.get(0).resetsAt());
  }

  @Test
  public void missingUtilizationAndMalformedWindowsCannotBecomeFakeZero() throws Exception {
    for (String row :
        new String[] {
          "{}",
          "{\"resets_at\":\"2026-10-24T08:00:00Z\"}",
          "{\"utilization\":null}",
          "[]",
          "12",
          "\"fake\""
        }) {
      JSONObject payload = json("{\"five_hour\":" + row + ",\"seven_day\":{\"utilization\":10}}");
      IOException error =
          assertThrows(
              IOException.class, () -> ClaudeUsageClient.parsePayload(payload, credential(), NOW));
      assertSanitized(error, TOKEN, REFRESH);
    }
  }

  @Test
  public void rejectsInvalidNumbersRatherThanClampingOrCoercing() throws Exception {
    for (String value :
        new String[] {
          "-0.1",
          "100.1",
          "100.00000000000000000001",
          "-1e-9999",
          "1e9999",
          "\"NaN\"",
          "\"Infinity\"",
          "\"12.5\"",
          "\"$12.50\"",
          "true",
          "{}",
          "[]",
          "null"
        }) {
      JSONObject payload = json("{\"five_hour\":{\"utilization\":" + value + "}}");
      assertThrows(
          IOException.class, () -> ClaudeUsageClient.parsePayload(payload, credential(), NOW));
    }
    // JSON text cannot contain non-finite numbers. Inject Numbers to exercise that guard.
    for (double value :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      JSONObject row =
          new JSONObject() {
            @Override
            public Object get(String key) {
              return value;
            }
          };
      JSONObject payload = new JSONObject().put("five_hour", row);
      assertThrows(
          IOException.class, () -> ClaudeUsageClient.parsePayload(payload, credential(), NOW));
    }
  }

  @Test
  public void malformedResetFailsWithoutEchoingItsValue() throws Exception {
    for (String reset : new String[] {"\"" + TOKEN + "\"", "\"\"", "123", "true", "{}"}) {
      JSONObject payload = json("{\"five_hour\":{\"utilization\":1,\"resets_at\":" + reset + "}}");
      IOException error =
          assertThrows(
              IOException.class, () -> ClaudeUsageClient.parsePayload(payload, credential(), NOW));
      assertSanitized(error, TOKEN);
    }
  }

  @Test
  public void emptyOrUnrecognizedPayloadThrowsInsteadOfShowingQuota() throws Exception {
    for (JSONObject payload :
        new JSONObject[] {
          null,
          json("{}"),
          json("{\"five_hour\":null,\"seven_day\":null}"),
          json("{\"extra_usage\":{\"utilization\":100}}")
        }) {
      assertThrows(
          IOException.class, () -> ClaudeUsageClient.parsePayload(payload, credential(), NOW));
    }
  }

  @Test
  public void transportFailuresNeverExposeExceptionMessagesCausesOrSuppressedErrors() {
    for (Exception failure :
        new Exception[] {
          new IOException("response body " + TOKEN),
          new IllegalArgumentException(REFRESH),
          new Exception(TOKEN, new Exception(REFRESH))
        }) {
      failure.addSuppressed(new IOException(REFRESH));
      ClaudeUsageClient client =
          new ClaudeUsageClient(
              (url, headers) -> {
                throw failure;
              });
      IOException error = assertThrows(IOException.class, () -> client.fetch(credential(), NOW));
      assertTrue(error.getMessage().contains("request failed"));
      assertSanitized(error, TOKEN, REFRESH, "response body");
    }
  }

  @Test
  public void transportStatusErrorsGiveSafeReimportOrWaitInstructions() {
    for (int status : new int[] {401, 403, 429, 302, 500}) {
      ClaudeUsageClient.HttpStatusException failure =
          new ClaudeUsageClient.HttpStatusException(status);
      failure.initCause(new IOException(TOKEN));
      failure.addSuppressed(new IOException(REFRESH));
      ClaudeUsageClient client =
          new ClaudeUsageClient(
              (url, headers) -> {
                throw failure;
              });
      ClaudeUsageClient.HttpStatusException error =
          assertThrows(
              ClaudeUsageClient.HttpStatusException.class, () -> client.fetch(credential(), NOW));
      assertEquals(status, error.statusCode());
      assertEquals(-1, error.retryAfterSeconds());
      assertTrue(error.getMessage().contains("HTTP " + status));
      if (status == 401 || status == 403) assertTrue(error.getMessage().contains("Reimport"));
      if (status == 429) assertTrue(error.getMessage().contains("Wait"));
      assertSanitized(error, TOKEN, REFRESH);
    }
  }

  @Test
  public void retryAfterExceptionMetadataIsOptionalAndBounded() {
    assertEquals(-1, new ClaudeUsageClient.HttpStatusException(429).retryAfterSeconds());
    for (long delay : new long[] {Long.MIN_VALUE, -99, -1, 0, 120, 86_400, Long.MAX_VALUE}) {
      ClaudeUsageClient.HttpStatusException error =
          new ClaudeUsageClient.HttpStatusException(429, delay);
      assertEquals(delay < 0 ? -1 : Math.min(86_400, delay), error.retryAfterSeconds());
      assertSanitized(error, TOKEN, REFRESH);
    }
  }

  @Test
  public void transportRetryAfterSurvivesSanitizationWithoutCausesOrSuppressedSecrets() {
    ClaudeUsageClient.HttpStatusException failure =
        new ClaudeUsageClient.HttpStatusException(429, 120);
    failure.initCause(new IOException(TOKEN));
    failure.addSuppressed(new IOException(REFRESH));
    ClaudeUsageClient client =
        new ClaudeUsageClient(
            (url, headers) -> {
              throw failure;
            });
    ClaudeUsageClient.HttpStatusException error =
        assertThrows(
            ClaudeUsageClient.HttpStatusException.class, () -> client.fetch(credential(), NOW));
    assertNotSame(failure, error);
    assertEquals(429, error.statusCode());
    assertEquals(120, error.retryAfterSeconds());
    assertSanitized(error, TOKEN, REFRESH);
  }

  @Test
  public void http429ParsesBoundedNumericRetryAfterWithoutReadingBodyOrRetrying() throws Exception {
    String[] values = {
      null,
      "120",
      " 0 ",
      "-1",
      "1.5",
      TOKEN,
      REFRESH,
      "120\r\n" + TOKEN,
      "120\t",
      "120\u0080",
      "9".repeat(65),
      "9223372036854775808",
      "86401"
    };
    long[] expected = {-1, 120, 0, -1, -1, -1, -1, -1, -1, -1, -1, 86_400, 86_400};
    for (int i = 0; i < values.length; i++) {
      FakeConnection connection = new FakeConnection(429, TOKEN + REFRESH);
      connection.retryAfter = values[i];
      ClaudeUsageClient client =
          new ClaudeUsageClient(
              (url, headers) -> ClaudeUsageClient.readResponse(connection, headers));
      ClaudeUsageClient.HttpStatusException error =
          assertThrows(
              ClaudeUsageClient.HttpStatusException.class, () -> client.fetch(credential(), NOW));
      assertEquals(429, error.statusCode());
      assertEquals(expected[i], error.retryAfterSeconds());
      assertEquals(1, connection.statusReads);
      assertEquals(1, connection.retryAfterReads);
      assertFalse(connection.bodyRead);
      assertFalse(connection.errorRead);
      assertTrue(connection.disconnected);
      assertSanitized(error, TOKEN, REFRESH);
    }
  }

  @Test
  public void http429ParsesDateRetryAfterAndCapsItAtOneDay() throws Exception {
    DateTimeFormatter httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    String[] values = {
      httpDate.format(Instant.now().plusSeconds(600)),
      httpDate.format(Instant.now().plusSeconds(864_000)),
      httpDate.format(Instant.EPOCH),
      "Sat, 99 Oct 2026 03:02:00 GMT"
    };
    for (int i = 0; i < values.length; i++) {
      FakeConnection connection = new FakeConnection(429, TOKEN + REFRESH);
      connection.retryAfter = values[i];
      ClaudeUsageClient client =
          new ClaudeUsageClient(
              (url, headers) -> ClaudeUsageClient.readResponse(connection, headers));
      ClaudeUsageClient.HttpStatusException error =
          assertThrows(
              ClaudeUsageClient.HttpStatusException.class, () -> client.fetch(credential(), NOW));
      if (i == 0) {
        assertTrue(error.retryAfterSeconds() > 0);
        assertTrue(error.retryAfterSeconds() <= 600);
      } else assertEquals(i == 1 ? 86_400 : i == 2 ? 0 : -1, error.retryAfterSeconds());
      assertEquals(1, connection.statusReads);
      assertEquals(1, connection.retryAfterReads);
      assertFalse(connection.bodyRead);
      assertFalse(connection.errorRead);
      assertTrue(connection.disconnected);
      assertSanitized(error, TOKEN, REFRESH, values[i]);
    }
  }

  @Test
  public void retryAfterHeaderIsNeverReadForOtherStatusesIncludingSuccess() throws Exception {
    for (int status : new int[] {200, 301, 401, 403, 500, 503}) {
      FakeConnection connection =
          new FakeConnection(status, "{\"five_hour\":{\"utilization\":1}}") {
            @Override
            public String getHeaderField(String name) {
              if ("Retry-After".equalsIgnoreCase(name)) {
                fail("Only HTTP 429 may read Retry-After");
              }
              return super.getHeaderField(name);
            }
          };
      ClaudeUsageClient client =
          new ClaudeUsageClient(
              (url, headers) -> ClaudeUsageClient.readResponse(connection, headers));
      if (status == 200) assertNotNull(client.fetch(credential(), NOW));
      else {
        ClaudeUsageClient.HttpStatusException error =
            assertThrows(
                ClaudeUsageClient.HttpStatusException.class, () -> client.fetch(credential(), NOW));
        assertEquals(status, error.statusCode());
        assertEquals(-1, error.retryAfterSeconds());
        assertSanitized(error, TOKEN, REFRESH);
      }
      assertEquals(0, connection.retryAfterReads);
      assertTrue(connection.disconnected);
    }
  }

  @Test
  public void retryAfterHeaderReadFailuresCannotExposeTransportSecrets() throws Exception {
    FakeConnection connection =
        new FakeConnection(429, TOKEN + REFRESH) {
          @Override
          public String getHeaderField(String name) {
            IOException cause = new IOException(REFRESH);
            throw new IllegalArgumentException(TOKEN, cause);
          }
        };
    ClaudeUsageClient client =
        new ClaudeUsageClient(
            (url, headers) -> ClaudeUsageClient.readResponse(connection, headers));
    IOException error = assertThrows(IOException.class, () -> client.fetch(credential(), NOW));
    assertSanitized(error, TOKEN, REFRESH);
    assertFalse(connection.bodyRead);
    assertFalse(connection.errorRead);
    assertTrue(connection.disconnected);
  }

  @Test
  public void interruptionPreventsTransportAndKeepsFlagSet() {
    ClaudeUsageClient client =
        new ClaudeUsageClient(
            (url, headers) -> {
              fail("Interrupted request must not call transport");
              return null;
            });
    Thread.currentThread().interrupt();
    try {
      assertThrows(InterruptedException.class, () -> client.fetch(credential(), NOW));
      assertThrows(
          InterruptedException.class,
          () -> ClaudeUsageClient.parsePayload(new JSONObject(), credential(), NOW));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void interruptionFromTransportIsSanitizedAndRestoresFlag() {
    ClaudeUsageClient client =
        new ClaudeUsageClient(
            (url, headers) -> {
              throw new InterruptedException(TOKEN);
            });
    try {
      InterruptedException error =
          assertThrows(InterruptedException.class, () -> client.fetch(credential(), NOW));
      assertSanitized(error, TOKEN);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void interruptionAfterTransportCannotReturnAReportOrLeakAnError() throws Exception {
    for (boolean fail : new boolean[] {false, true}) {
      ClaudeUsageClient client =
          new ClaudeUsageClient(
              (url, headers) -> {
                Thread.currentThread().interrupt();
                if (fail) throw new IOException(TOKEN);
                return json("{\"five_hour\":{\"utilization\":1}}");
              });
      try {
        InterruptedException error =
            assertThrows(InterruptedException.class, () -> client.fetch(credential(), NOW));
        assertSanitized(error, TOKEN);
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  public void httpPolicyUsesGetNoRedirectsAndTenTwentySecondTimeouts() throws Exception {
    FakeConnection connection = new FakeConnection(200, "{\"five_hour\":{\"utilization\":1}}");
    Map<String, String> headers =
        Map.of("Authorization", "Bearer " + TOKEN, "anthropic-beta", "oauth-2025-04-20");
    ClaudeUsageClient client =
        new ClaudeUsageClient(
            (url, requestHeaders) -> ClaudeUsageClient.readResponse(connection, requestHeaders));
    assertEquals(1, client.fetch(credential(), NOW).windows.size());
    assertEquals("GET", connection.getRequestMethod());
    assertFalse(connection.getInstanceFollowRedirects());
    assertEquals(10_000, connection.getConnectTimeout());
    assertEquals(20_000, connection.getReadTimeout());
    assertFalse(connection.getUseCaches());
    for (Map.Entry<String, String> header : headers.entrySet()) {
      assertEquals(header.getValue(), connection.getRequestProperty(header.getKey()));
    }
    assertTrue(connection.disconnected);
    assertTrue(connection.bodyClosed);
    assertFalse(connection.errorRead);
  }

  @Test
  public void httpErrorsNeverReadBodyOrFollowRedirectLocation() throws Exception {
    for (int status : new int[] {301, 302, 307, 308, 401, 403, 429, 500}) {
      FakeConnection connection = new FakeConnection(status, TOKEN + REFRESH);
      ClaudeUsageClient client =
          new ClaudeUsageClient(
              (url, headers) -> ClaudeUsageClient.readResponse(connection, headers));
      IOException error = assertThrows(IOException.class, () -> client.fetch(credential(), NOW));
      assertTrue(error.getMessage().contains("HTTP " + status));
      assertSanitized(error, TOKEN, REFRESH);
      assertFalse(connection.bodyRead);
      assertFalse(connection.errorRead);
      assertFalse(connection.getInstanceFollowRedirects());
      assertTrue(connection.disconnected);
    }
  }

  @Test
  public void httpBodyBoundAcceptsExactlyOneMiBAndRejectsMoreWithAndWithoutLength()
      throws Exception {
    String payload = "{\"five_hour\":{\"utilization\":1}}";
    FakeConnection exact =
        new FakeConnection(200, payload + " ".repeat(MAX_BYTES - payload.length()));
    assertNotNull(ClaudeUsageClient.readResponse(exact, Collections.emptyMap()));
    assertTrue(exact.bodyClosed);
    for (long length : new long[] {-1, MAX_BYTES + 1}) {
      FakeConnection connection = new FakeConnection(200, " ".repeat(MAX_BYTES + 1));
      connection.contentLength = length;
      IOException error =
          assertThrows(
              IOException.class,
              () -> ClaudeUsageClient.readResponse(connection, Collections.emptyMap()));
      assertTrue(error.getMessage().contains("1 MiB"));
      assertSanitized(error, TOKEN, REFRESH);
      assertTrue(connection.disconnected);
      if (length < 0) assertTrue(connection.bodyClosed);
      else assertFalse(connection.bodyRead);
    }
  }

  @Test
  public void malformedHttpJsonIsSanitizedAndConnectionClosed() throws Exception {
    for (String body : new String[] {"{broken-" + TOKEN, "[]", "{} " + REFRESH}) {
      FakeConnection connection = new FakeConnection(200, body);
      IOException error =
          assertThrows(
              IOException.class,
              () -> ClaudeUsageClient.readResponse(connection, Collections.emptyMap()));
      assertSanitized(error, TOKEN, REFRESH, body);
      assertTrue(connection.disconnected);
      assertTrue(connection.bodyClosed);
    }
  }

  @Test
  public void interruptionWhileReadingClosesBodyAndDisconnects() throws Exception {
    FakeConnection connection =
        new FakeConnection(200, "{}") {
          @Override
          public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[] {'{', '}'}) {
              @Override
              public synchronized int read(byte[] bytes, int offset, int length) {
                Thread.currentThread().interrupt();
                return super.read(bytes, offset, length);
              }

              @Override
              public void close() throws IOException {
                bodyClosed = true;
                super.close();
              }
            };
          }
        };
    try {
      assertThrows(
          InterruptedException.class,
          () -> ClaudeUsageClient.readResponse(connection, Collections.emptyMap()));
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(connection.bodyClosed);
      assertTrue(connection.disconnected);
    } finally {
      Thread.interrupted();
    }
  }

  /** In-memory connection: never opens a socket, reads credentials, or contacts a server. */
  private static class FakeConnection extends HttpURLConnection {
    private final int status;
    private final byte[] body;
    long contentLength = -1;
    String retryAfter;
    int retryAfterReads;
    int statusReads;
    boolean bodyRead;
    boolean errorRead;
    boolean bodyClosed;
    boolean disconnected;

    FakeConnection(int status, String body) throws Exception {
      super(new URL("https://api.anthropic.com/api/oauth/usage"));
      this.status = status;
      this.body = body.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int getResponseCode() {
      statusReads++;
      return status;
    }

    @Override
    public long getContentLengthLong() {
      return contentLength;
    }

    @Override
    public InputStream getInputStream() {
      bodyRead = true;
      return new ByteArrayInputStream(body) {
        @Override
        public void close() throws IOException {
          bodyClosed = true;
          super.close();
        }
      };
    }

    @Override
    public InputStream getErrorStream() {
      errorRead = true;
      throw new AssertionError("Error response bodies must never be read");
    }

    @Override
    public String getHeaderField(String name) {
      if ("Retry-After".equalsIgnoreCase(name)) {
        retryAfterReads++;
        return retryAfter;
      }
      if ("Location".equalsIgnoreCase(name)) return "https://example.invalid/" + TOKEN;
      return null;
    }

    @Override
    public void connect() {
      throw new AssertionError("No real requests permitted");
    }

    @Override
    public boolean usingProxy() {
      return false;
    }

    @Override
    public void disconnect() {
      disconnected = true;
    }
  }
}
