package local.aimonitor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

public class CodexUsageClientTest {
  private static final String URL = "https://chatgpt.com/backend-api/wham/usage";
  private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
  private static final int MIB = 1024 * 1024;
  private static final String TOKEN = "synthetic-access-token";
  private static final String ACCOUNT = "synthetic-credential-account";
  private static final String REFRESH = "synthetic-refresh-secret";
  private static final String ID_TOKEN = "synthetic-id-secret";
  private static final String API_KEY = "sk-synthetic-api-secret";
  private static final String EMAIL = "synthetic-private@example.invalid";
  private static final String USER_ID = "synthetic-private-user";
  private static final String RETURNED_ACCOUNT = "synthetic-response-account";

  private static CodexUsageClient.Credential credential() {
    return new CodexUsageClient.Credential(TOKEN, ACCOUNT);
  }

  private static JSONObject json(String text) throws Exception {
    return new JSONObject(text);
  }

  private static String fullCredential() {
    return "{\"OPENAI_API_KEY\":\""
        + API_KEY
        + "\",\"tokens\":{\"access_token\":\""
        + TOKEN
        + "\",\"account_id\":\""
        + ACCOUNT
        + "\",\"refresh_token\":\""
        + REFRESH
        + "\",\"id_token\":\""
        + ID_TOKEN
        + "\"}}";
  }

  private static JSONObject row(Object used, Object seconds) throws Exception {
    return new JSONObject().put("used_percent", used).put("limit_window_seconds", seconds);
  }

  private static JSONObject payload(JSONObject row) throws Exception {
    return new JSONObject().put("rate_limit", new JSONObject().put("primary_window", row));
  }

  private static JSONObject actualPayload() throws Exception {
    return json(
        """
        {"plan_type":"plus","account_id":"synthetic-response-account",
         "email":"synthetic-private@example.invalid","user_id":"synthetic-private-user",
         "rate_limit":{"allowed":true,"limit_reached":false,
          "primary_window":{"used_percent":52,"limit_window_seconds":18000,
           "reset_at":1792839600,"reset_after_seconds":123},
          "secondary_window":{"used_percent":11.5,"limit_window_seconds":604800,
           "reset_after_seconds":600}},
         "code_review_rate_limit":{"primary_window":{"used_percent":"ignored"}},
         "credits":{"balance":"ignored"}}
        """);
  }

  private static void assertSanitized(Throwable error, String... extras) {
    assertNull(error.getCause());
    assertEquals(0, error.getSuppressed().length);
    StringWriter stack = new StringWriter();
    error.printStackTrace(new PrintWriter(stack));
    for (String secret :
        new String[] {
          TOKEN, ACCOUNT, REFRESH, ID_TOKEN, API_KEY, EMAIL, USER_ID, RETURNED_ACCOUNT
        }) {
      assertFalse("Exception leaked synthetic secret", stack.toString().contains(secret));
    }
    for (String extra : extras) {
      if (extra != null && !extra.isEmpty()) assertFalse(stack.toString().contains(extra));
    }
  }

  @Test
  public void fullAuthRetainsOnlyAccessTokenAndAccountAndRedactsToString() throws Exception {
    String source = fullCredential();
    CodexUsageClient.Credential parsed = CodexUsageClient.parseCredential(source, NOW);
    assertEquals(TOKEN, parsed.accessToken());
    assertEquals(ACCOUNT, parsed.accountId());
    assertEquals("Credential[redacted]", parsed.toString());
    assertEquals(
        List.of("accessToken", "accountId"),
        Arrays.stream(parsed.getClass().getRecordComponents()).map(c -> c.getName()).toList());
    assertEquals(2, parsed.getClass().getDeclaredFields().length);
    for (String secret : new String[] {source, TOKEN, ACCOUNT, REFRESH, ID_TOKEN, API_KEY}) {
      assertFalse(parsed.toString().contains(secret));
    }
  }

  @Test
  public void acceptsMinimalFullNormalizedAndBareOpaqueTokensWithoutJwtOrExpiry() throws Exception {
    for (String source :
        new String[] {
          TOKEN,
          "{\"tokens\":{\"access_token\":\"" + TOKEN + "\"}}",
          "{\"accessToken\":\"" + TOKEN + "\"}",
          "{\"accessToken\":\"" + TOKEN + "\",\"accountId\":null}",
          "{\"tokens\":{\"access_token\":\""
              + TOKEN
              + "\"},\"expires_at\":0,\"last_refresh\":\"ignored\"}"
        }) {
      CodexUsageClient.Credential parsed = CodexUsageClient.parseCredential(source, NOW);
      assertEquals(TOKEN, parsed.accessToken());
      assertNull(parsed.accountId());
    }
    String normalized = "{\"accessToken\":\"" + TOKEN + "\",\"accountId\":\"" + ACCOUNT + "\"}";
    assertEquals(credential(), CodexUsageClient.parseCredential(normalized, NOW));
    assertEquals(
        credential(), CodexUsageClient.parseCredential(" \n" + fullCredential() + "\t", NOW));
  }

  @Test
  public void rejectsApiKeysWithoutUsingOtherCredentialFieldsAsFallback() {
    for (String source :
        new String[] {
          API_KEY,
          "{\"OPENAI_API_KEY\":\"" + API_KEY + "\"}",
          "{\"tokens\":{\"refresh_token\":\"" + REFRESH + "\",\"id_token\":\"" + ID_TOKEN + "\"}}",
          "{\"accessToken\":\"" + API_KEY + "\"}",
          "{\"tokens\":{\"access_token\":\"" + API_KEY + "\"}}",
          "{\"tokens\":{},\"accessToken\":\"" + TOKEN + "\"}"
        }) {
      IOException error =
          assertThrows(IOException.class, () -> CodexUsageClient.parseCredential(source, NOW));
      assertSanitized(error, source);
    }
  }

  @Test
  public void rejectsEmptyControlsAndNonAsciiTokensAtImportAndBeforeTransport() throws Exception {
    CodexUsageClient client =
        new CodexUsageClient(
            (url, headers) -> {
              fail("Invalid token must not reach transport");
              return null;
            });
    for (String value :
        new String[] {
          "",
          " ",
          " " + TOKEN,
          TOKEN + "\n",
          "fake token",
          "fake\r\nX-Header:injected",
          "fake\u0000",
          "fake\tvalue",
          "fake\u007f",
          "fake한글",
          "fake\u2003",
          "[fake]",
          "\"fake\"",
          "fake\\value",
          API_KEY
        }) {
      assertSanitized(
          assertThrows(IOException.class, () -> CodexUsageClient.parseCredential(value, NOW)));
      assertSanitized(
          assertThrows(
              IOException.class,
              () -> client.fetch(new CodexUsageClient.Credential(value, null), NOW)));
      String encoded = new JSONObject().put("accessToken", value).toString();
      assertSanitized(
          assertThrows(IOException.class, () -> CodexUsageClient.parseCredential(encoded, NOW)));
    }
    assertThrows(IOException.class, () -> CodexUsageClient.parseCredential(null, NOW));
    assertThrows(IOException.class, () -> CodexUsageClient.parseCredential(TOKEN, null));
    assertThrows(IOException.class, () -> client.fetch(null, NOW));
    assertThrows(IOException.class, () -> client.fetch(credential(), null));
    assertThrows(
        IOException.class, () -> client.fetch(new CodexUsageClient.Credential(null, null), NOW));
  }

  @Test
  public void validatesOptionalAccountInBothSchemasAndDirectCredentials() throws Exception {
    CodexUsageClient client =
        new CodexUsageClient(
            (url, headers) -> {
              fail("Invalid account must not reach transport");
              return null;
            });
    for (Object value :
        new Object[] {
          "",
          " ",
          "account\n",
          "account\r\nAuthorization:injected",
          "account\u0000",
          "account\u007f",
          "계정",
          "[account]",
          "x".repeat(257),
          12,
          true,
          new JSONObject()
        }) {
      for (boolean full : new boolean[] {true, false}) {
        JSONObject source =
            new JSONObject()
                .put(full ? "access_token" : "accessToken", TOKEN)
                .put(full ? "account_id" : "accountId", value);
        String text = (full ? new JSONObject().put("tokens", source) : source).toString();
        assertSanitized(
            assertThrows(IOException.class, () -> CodexUsageClient.parseCredential(text, NOW)));
      }
      if (value instanceof String) {
        assertSanitized(
            assertThrows(
                IOException.class,
                () -> client.fetch(new CodexUsageClient.Credential(TOKEN, (String) value), NOW)));
      }
    }
    String maximum = "a".repeat(256);
    assertEquals(
        maximum,
        CodexUsageClient.parseCredential(
                new JSONObject().put("accessToken", TOKEN).put("accountId", maximum).toString(),
                NOW)
            .accountId());
  }

  @Test
  public void malformedAndDeepCredentialsGiveCauseLessErrors() {
    for (String text :
        new String[] {
          "{broken-" + REFRESH,
          "{}",
          "[]",
          "{\"tokens\":null}",
          "{\"tokens\":[]}",
          "{\"accessToken\":12}",
          "{\"accessToken\":null}",
          fullCredential() + " trailing",
          "{\"extra\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}"
        }) {
      assertSanitized(
          assertThrows(IOException.class, () -> CodexUsageClient.parseCredential(text, NOW)), text);
    }
  }

  @Test
  public void credentialLimitCountsOriginalUtf8BytesAndAcceptsExactlyOneMiB() throws Exception {
    String prefix = "{\"accessToken\":\"" + TOKEN + "\",\"padding\":\"";
    String exact = prefix + "x".repeat(MIB - prefix.length() - 2) + "\"}";
    assertEquals(MIB, exact.getBytes(StandardCharsets.UTF_8).length);
    assertEquals(TOKEN, CodexUsageClient.parseCredential(exact, NOW).accessToken());
    assertEquals(
        MIB, CodexUsageClient.parseCredential("a".repeat(MIB), NOW).accessToken().length());
    for (String text :
        new String[] {exact + " ", "a".repeat(MIB + 1), prefix + "한".repeat(MIB / 3) + "\"}"}) {
      assertSanitized(
          assertThrows(IOException.class, () -> CodexUsageClient.parseCredential(text, NOW)));
    }
  }

  @Test
  public void fixedUrlAndExactImmutableHeadersUseOnlyCredentialAccount() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CodexUsageClient client =
        new CodexUsageClient(
            (url, headers) -> {
              calls.incrementAndGet();
              assertEquals(URL, url);
              assertFalse(url.contains(TOKEN));
              assertFalse(url.contains("?"));
              assertEquals(
                  Map.of(
                      "Authorization",
                      "Bearer " + TOKEN,
                      "ChatGPT-Account-Id",
                      ACCOUNT,
                      "User-Agent",
                      "CodeSpend/4.0 Android",
                      "Accept",
                      "application/json"),
                  headers);
              assertThrows(
                  UnsupportedOperationException.class, () -> headers.put("extra", "value"));
              return actualPayload();
            });
    UsageReport report = client.fetch(CodexUsageClient.parseCredential(fullCredential(), NOW), NOW);
    assertEquals(1, calls.get());
    assertEquals("plus", report.plan);
    assertEquals(NOW, report.asOf);
    assertTrue(report.usage.isEmpty());
    assertEquals(52, report.windows.get(0).usedPercent(), 0);
    String retained = report.plan + report.asOf + report.windows + report.usage;
    for (String secret :
        new String[] {
          TOKEN, ACCOUNT, REFRESH, ID_TOKEN, API_KEY, EMAIL, USER_ID, RETURNED_ACCOUNT
        }) {
      assertFalse(retained.contains(secret));
    }
  }

  @Test
  public void omitsAccountHeaderWhenCredentialHasNoneEvenIfPayloadReturnsOne() throws Exception {
    CodexUsageClient client =
        new CodexUsageClient(
            (url, headers) -> {
              assertEquals(
                  Map.of(
                      "Authorization",
                      "Bearer " + TOKEN,
                      "User-Agent",
                      "CodeSpend/4.0 Android",
                      "Accept",
                      "application/json"),
                  headers);
              return actualPayload();
            });
    client.fetch(new CodexUsageClient.Credential(TOKEN, null), NOW);
    client.fetch(new CodexUsageClient.Credential(TOKEN, null), NOW);
  }

  @Test
  public void actualShapeHasTwoOrderedWindowsAndEpochTakesPrecedenceOverFallback()
      throws Exception {
    UsageReport report = CodexUsageClient.parsePayload(actualPayload(), NOW);
    assertEquals(
        List.of("primary_window", "secondary_window"),
        report.windows.stream().map(w -> w.id()).toList());
    assertEquals("5시간 세션", report.windows.get(0).label());
    assertEquals("주간", report.windows.get(1).label());
    assertEquals(52, report.windows.get(0).usedPercent(), 0);
    assertEquals(11.5, report.windows.get(1).usedPercent(), 0);
    assertEquals(Instant.ofEpochSecond(1792839600), report.windows.get(0).resetsAt());
    assertEquals(NOW.plusSeconds(600), report.windows.get(1).resetsAt());
    assertEquals(NOW, report.asOf);
    assertTrue(report.usage.isEmpty());
    assertThrows(UnsupportedOperationException.class, report.windows::clear);
  }

  @Test
  public void allowsOneWindowAndOptionalResetAndOnlyStringPlans() throws Exception {
    for (Object plan : new Object[] {JSONObject.NULL, 123, true, new JSONObject()}) {
      JSONObject source =
          new JSONObject()
              .put("plan_type", plan)
              .put(
                  "rate_limit",
                  new JSONObject()
                      .put("primary_window", JSONObject.NULL)
                      .put("secondary_window", row(0, 604800)));
      UsageReport report = CodexUsageClient.parsePayload(source, NOW);
      assertNull(report.plan);
      assertEquals(1, report.windows.size());
      assertEquals("secondary_window", report.windows.get(0).id());
      assertNull(report.windows.get(0).resetsAt());
      assertEquals(0, report.windows.get(0).usedPercent(), 0);
    }
    assertNull(CodexUsageClient.parsePayload(payload(row(100, 18000)), NOW).plan);
    assertEquals(
        100,
        CodexUsageClient.parsePayload(payload(row(100, 18000)), NOW).windows.get(0).usedPercent(),
        0);
  }

  @Test
  public void otherDurationsGetAccurateLabels() throws Exception {
    for (int seconds : new int[] {3600, 90 * 60, 91}) {
      String label = seconds == 3600 ? "1시간" : seconds == 5400 ? "90분" : "91초";
      assertEquals(
          label,
          CodexUsageClient.parsePayload(payload(row(25, seconds)), NOW).windows.get(0).label());
    }
  }

  @Test
  public void missingWindowsOrWrongSchemaNeverInventsQuota() throws Exception {
    for (JSONObject source :
        new JSONObject[] {
          null,
          json("{}"),
          json("{\"rate_limit\":null}"),
          json("{\"rate_limit\":[]}"),
          json("{\"rate_limit\":{}}"),
          json("{\"rate_limit\":{\"primary_window\":null,\"secondary_window\":null}}"),
          json("{\"rate_limits\":{\"primary\":{\"used_percent\":52,\"window_minutes\":300}}}"),
          json(
              "{\"code_review_rate_limit\":{\"primary_window\":{\"used_percent\":1,\"limit_window_seconds\":18000}}}")
        }) {
      assertSanitized(
          assertThrows(IOException.class, () -> CodexUsageClient.parsePayload(source, NOW)));
    }
    assertThrows(IOException.class, () -> CodexUsageClient.parsePayload(actualPayload(), null));
  }

  @Test
  public void missingRequiredFieldsAndMalformedPresentWindowRejectEntireReport() throws Exception {
    for (String invalid :
        new String[] {
          "{}",
          "{\"used_percent\":52}",
          "{\"limit_window_seconds\":18000}",
          "[]",
          "12",
          "\"" + EMAIL + "\""
        }) {
      JSONObject source =
          json(
              "{\"rate_limit\":{\"primary_window\":"
                  + invalid
                  + ",\"secondary_window\":{\"used_percent\":1,\"limit_window_seconds\":604800}}}");
      assertSanitized(
          assertThrows(IOException.class, () -> CodexUsageClient.parsePayload(source, NOW)));
    }
  }

  @Test
  public void rejectsNonnumericNonfiniteAndOutOfRangePercentWithoutCoercion() throws Exception {
    for (String invalid :
        new String[] {
          "null",
          "true",
          "{}",
          "[]",
          "\"52\"",
          "\"NaN\"",
          "\"Infinity\"",
          "NaN",
          "-0.1",
          "100.1",
          "100.00000000000000000001",
          "-1e-9999",
          "1e9999"
        }) {
      JSONObject source =
          json(
              "{\"rate_limit\":{\"primary_window\":{\"used_percent\":"
                  + invalid
                  + ",\"limit_window_seconds\":18000}}}");
      assertSanitized(
          assertThrows(IOException.class, () -> CodexUsageClient.parsePayload(source, NOW)));
    }
    for (double number :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      JSONObject injected =
          new JSONObject() {
            @Override
            public Object get(String key) {
              return key.equals("used_percent") ? number : 18000;
            }
          };
      JSONObject source = payload(injected);
      assertSanitized(
          assertThrows(IOException.class, () -> CodexUsageClient.parsePayload(source, NOW)));
    }
  }

  @Test
  public void windowDurationMustBePositiveIntegerWithinIntRange() throws Exception {
    for (Object invalid :
        new Object[] {
          JSONObject.NULL,
          0,
          -1,
          1.5,
          "18000",
          true,
          new JSONObject(),
          2147483648L,
          new java.math.BigInteger("9223372036854775808")
        }) {
      JSONObject source = payload(row(52, invalid));
      assertSanitized(
          assertThrows(IOException.class, () -> CodexUsageClient.parsePayload(source, NOW)));
    }
  }

  @Test
  public void malformedResetIsRejectedAndNullEpochDoesNotEnableFallback() throws Exception {
    for (String key : new String[] {"reset_at", "reset_after_seconds"}) {
      for (Object invalid :
          new Object[] {
            JSONObject.NULL, "123", EMAIL, true, -1, 0.5, Long.MAX_VALUE, new JSONObject()
          }) {
        JSONObject source = payload(row(52, 18000).put(key, invalid));
        if (key.equals("reset_at")) {
          source
              .getJSONObject("rate_limit")
              .getJSONObject("primary_window")
              .put("reset_after_seconds", 1);
        }
        assertSanitized(
            assertThrows(IOException.class, () -> CodexUsageClient.parsePayload(source, NOW)));
      }
    }
    assertEquals(
        Instant.EPOCH,
        CodexUsageClient.parsePayload(payload(row(52, 18000).put("reset_at", 0)), NOW)
            .windows
            .get(0)
            .resetsAt());
    assertEquals(
        NOW,
        CodexUsageClient.parsePayload(payload(row(52, 18000).put("reset_after_seconds", 0)), NOW)
            .windows
            .get(0)
            .resetsAt());
  }

  @Test
  public void genericTransportErrorsAreSanitizedWithoutRetries() {
    for (Exception failure :
        new Exception[] {
          new IOException("body " + TOKEN),
          new IllegalArgumentException(EMAIL),
          new Exception(ACCOUNT, new Exception(REFRESH))
        }) {
      failure.addSuppressed(new IOException(ID_TOKEN));
      AtomicInteger calls = new AtomicInteger();
      CodexUsageClient client =
          new CodexUsageClient(
              (url, headers) -> {
                calls.incrementAndGet();
                throw failure;
              });
      assertSanitized(assertThrows(IOException.class, () -> client.fetch(credential(), NOW)));
      assertEquals(1, calls.get());
    }
  }

  @Test
  public void typedStatusErrorsRetainOnlyStatusAndBackoffWithSafeInstructions() {
    for (int status : new int[] {301, 302, 401, 403, 429, 500}) {
      CodexUsageClient.HttpStatusException failure =
          new CodexUsageClient.HttpStatusException(status, 120);
      failure.initCause(new IOException(TOKEN));
      failure.addSuppressed(new IOException(REFRESH));
      AtomicInteger calls = new AtomicInteger();
      CodexUsageClient client =
          new CodexUsageClient(
              (url, headers) -> {
                calls.incrementAndGet();
                throw failure;
              });
      CodexUsageClient.HttpStatusException error =
          assertThrows(
              CodexUsageClient.HttpStatusException.class, () -> client.fetch(credential(), NOW));
      assertEquals(status, error.statusCode());
      assertEquals(120, error.retryAfterSeconds());
      assertTrue(error.getMessage().contains("HTTP " + status));
      if (status == 401 || status == 403) assertTrue(error.getMessage().contains("Reimport"));
      if (status == 429) assertTrue(error.getMessage().contains("Wait"));
      assertSanitized(error);
      assertEquals(1, calls.get());
    }
    assertEquals(-1, new CodexUsageClient.HttpStatusException(429).retryAfterSeconds());
    assertEquals(-1, new CodexUsageClient.HttpStatusException(429, -99).retryAfterSeconds());
    assertEquals(
        86400, new CodexUsageClient.HttpStatusException(429, Long.MAX_VALUE).retryAfterSeconds());
  }

  @Test
  public void httpUsesReadOnlyGetWithFixedEndpointTimeoutsAndNoRedirects() throws Exception {
    boolean globalRedirects = HttpURLConnection.getFollowRedirects();
    FakeConnection connection = new FakeConnection(200, actualPayload().toString());
    CodexUsageClient client =
        new CodexUsageClient((url, headers) -> CodexUsageClient.readResponse(connection, headers));
    assertEquals(52, client.fetch(credential(), NOW).windows.get(0).usedPercent(), 0);
    assertEquals("GET", connection.getRequestMethod());
    assertFalse(connection.getDoOutput());
    assertTrue(connection.getDoInput());
    assertFalse(connection.getAllowUserInteraction());
    assertFalse(connection.getInstanceFollowRedirects());
    assertFalse(connection.getUseCaches());
    assertEquals(10_000, connection.getConnectTimeout());
    assertEquals(20_000, connection.getReadTimeout());
    assertEquals(globalRedirects, HttpURLConnection.getFollowRedirects());
    assertEquals("Bearer " + TOKEN, connection.getRequestProperty("Authorization"));
    assertEquals(ACCOUNT, connection.getRequestProperty("ChatGPT-Account-Id"));
    assertEquals("CodeSpend/4.0 Android", connection.getRequestProperty("User-Agent"));
    assertEquals("application/json", connection.getRequestProperty("Accept"));
    assertTrue(connection.bodyClosed);
    assertTrue(connection.disconnected);
    assertFalse(connection.errorRead);
    assertEquals(1, connection.statusReads);
  }

  @Test
  public void rejectsAnyOtherConnectionUrlBeforeSendingCredentialsOrReadingStatus()
      throws Exception {
    for (String address :
        new String[] {
          "http://chatgpt.com/backend-api/wham/usage",
          URL + "?token=" + TOKEN,
          URL + "/",
          URL + "#fragment",
          "https://chatgpt.com:443/backend-api/wham/usage",
          "https://example.invalid/backend-api/wham/usage",
          "https://chatgpt.com/backend-api/wham/refresh"
        }) {
      FakeConnection connection = new FakeConnection(address, 200, "{}");
      assertSanitized(
          assertThrows(
              IOException.class,
              () ->
                  CodexUsageClient.readResponse(
                      connection, Map.of("Authorization", "Bearer " + TOKEN))));
      assertNull(connection.getRequestProperty("Authorization"));
      assertEquals(0, connection.statusReads);
      assertFalse(connection.bodyRead);
      assertTrue(connection.disconnected);
    }
  }

  @Test
  public void redirectsAndHttpErrorsNeverReadBodiesOrFollowLocations() throws Exception {
    for (int status : new int[] {201, 204, 301, 302, 303, 307, 308, 401, 403, 429, 500}) {
      FakeConnection connection = new FakeConnection(status, TOKEN + EMAIL);
      CodexUsageClient.HttpStatusException error =
          assertThrows(
              CodexUsageClient.HttpStatusException.class,
              () -> CodexUsageClient.readResponse(connection, Collections.emptyMap()));
      assertEquals(status, error.statusCode());
      assertSanitized(error);
      assertFalse(connection.bodyRead);
      assertFalse(connection.errorRead);
      assertFalse(connection.locationRead);
      assertFalse(connection.getInstanceFollowRedirects());
      assertTrue(connection.disconnected);
      assertEquals(1, connection.statusReads);
    }
  }

  @Test
  public void retryAfterIsOptionalBoundedNumericMetadataAndNeverTriggersAutomaticRetry()
      throws Exception {
    for (String value :
        new String[] {
          null,
          "120",
          " 0 ",
          "-1",
          "1.5",
          TOKEN,
          "120\r\nInjected:value",
          "9".repeat(100),
          "9223372036854775808"
        }) {
      FakeConnection connection = new FakeConnection(429, TOKEN);
      connection.retryAfter = value;
      CodexUsageClient client =
          new CodexUsageClient(
              (url, headers) -> CodexUsageClient.readResponse(connection, headers));
      CodexUsageClient.HttpStatusException error =
          assertThrows(
              CodexUsageClient.HttpStatusException.class, () -> client.fetch(credential(), NOW));
      long expected =
          "120".equals(value)
              ? 120
              : " 0 ".equals(value) ? 0 : "9223372036854775808".equals(value) ? 86400 : -1;
      assertEquals(expected, error.retryAfterSeconds());
      assertEquals(1, connection.statusReads);
      assertSanitized(error);
    }
  }

  @Test
  public void retryAfterParsesUtcDatesAndCapsNumericOrDateDelays() {
    DateTimeFormatter httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    assertEquals(120, CodexUsageClient.retryAfter(httpDate.format(NOW.plusSeconds(120)), NOW));
    assertEquals(
        120,
        CodexUsageClient.retryAfter(
            httpDate.format(NOW.plusSeconds(120)), NOW.plusNanos(500_000_000)));
    assertEquals(0, CodexUsageClient.retryAfter(httpDate.format(NOW.minusSeconds(60)), NOW));
    assertEquals(0, CodexUsageClient.retryAfter(httpDate.format(NOW), NOW));
    assertEquals(86400, CodexUsageClient.retryAfter(httpDate.format(NOW.plusSeconds(864000)), NOW));
    assertEquals(86400, CodexUsageClient.retryAfter("999999999999999999999999999999", NOW));
    assertEquals(86400, CodexUsageClient.retryAfter("86401", NOW));
    assertEquals(86400, CodexUsageClient.retryAfter("86400", NOW));
    assertEquals(86399, CodexUsageClient.retryAfter("86399", NOW));
    for (String invalid :
        new String[] {
          "",
          " ",
          TOKEN,
          "-1",
          "1.5",
          "120\r\n",
          "120\t",
          "9".repeat(65),
          "Sat, 24 Oct 2026 03:02:00 BAD",
          "Sat, 99 Oct 2026 03:02:00 GMT"
        }) {
      assertEquals(-1, CodexUsageClient.retryAfter(invalid, NOW));
    }
    assertEquals(-1, CodexUsageClient.retryAfter(null, NOW));
    assertEquals(-1, CodexUsageClient.retryAfter("120", null));
  }

  @Test
  public void http429PreservesUtcDateBackoffAsBoundedTypedMetadata() throws Exception {
    FakeConnection connection = new FakeConnection(429, TOKEN);
    connection.retryAfter =
        DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC)
            .format(Instant.now().plusSeconds(600));
    CodexUsageClient.HttpStatusException error =
        assertThrows(
            CodexUsageClient.HttpStatusException.class,
            () -> CodexUsageClient.readResponse(connection, Collections.emptyMap()));
    assertTrue(error.retryAfterSeconds() > 0);
    assertTrue(error.retryAfterSeconds() <= 600);
    assertEquals(1, connection.statusReads);
    assertFalse(connection.bodyRead);
    assertSanitized(error);
  }

  @Test
  public void credentialJsonNestingAllows64AndRejects65BeforeRecursiveParsing() throws Exception {
    // Root object contributes one level; brackets inside quoted fields contribute none.
    String prefix = "{\"accessToken\":\"" + TOKEN + "\",\"padding\":\"[[[\",\"ignored\":";
    String atLimit = prefix + "[".repeat(63) + "0" + "]".repeat(63) + "}";
    assertEquals(TOKEN, CodexUsageClient.parseCredential(atLimit, NOW).accessToken());
    String aboveLimit = prefix + "[".repeat(64) + "0" + "]".repeat(64) + "}";
    assertSanitized(
        assertThrows(IOException.class, () -> CodexUsageClient.parseCredential(aboveLimit, NOW)));
  }

  @Test
  public void responseByteBoundAllowsExactlyOneMiBAndRejectsKnownOrStreamedOversize()
      throws Exception {
    String body = actualPayload().toString();
    FakeConnection exact = new FakeConnection(200, body + " ".repeat(MIB - body.length()));
    assertNotNull(CodexUsageClient.readResponse(exact, Collections.emptyMap()));
    assertTrue(exact.bodyClosed);
    for (long length : new long[] {-1, MIB + 1}) {
      FakeConnection connection = new FakeConnection(200, "x".repeat(MIB + 1));
      connection.contentLength = length;
      IOException error =
          assertThrows(
              IOException.class,
              () -> CodexUsageClient.readResponse(connection, Collections.emptyMap()));
      assertTrue(error.getMessage().contains("1 MiB"));
      assertSanitized(error);
      assertTrue(connection.disconnected);
      if (length == -1) assertTrue(connection.bodyClosed);
      else assertFalse(connection.bodyRead);
    }
    FakeConnection utf8 = new FakeConnection(200, "{\"padding\":\"" + "한".repeat(MIB / 3) + "\"}");
    assertThrows(
        IOException.class, () -> CodexUsageClient.readResponse(utf8, Collections.emptyMap()));
    assertTrue(utf8.bodyClosed);
  }

  @Test
  public void malformedResponseJsonAndDeepNestingAreSanitizedAndClosed() throws Exception {
    for (String body :
        new String[] {
          "{broken-" + TOKEN,
          "[]",
          "{} " + EMAIL,
          "{\"extra\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}"
        }) {
      FakeConnection connection = new FakeConnection(200, body);
      assertSanitized(
          assertThrows(
              IOException.class,
              () -> CodexUsageClient.readResponse(connection, Collections.emptyMap())),
          body);
      assertTrue(connection.bodyClosed);
      assertTrue(connection.disconnected);
    }
  }

  @Test
  public void readAndCloseFailuresCannotLeakCausesOrSuppressedExceptions() throws Exception {
    FakeConnection connection =
        new FakeConnection(200, "{}") {
          @Override
          public InputStream getInputStream() {
            return new InputStream() {
              @Override
              public int read() throws IOException {
                throw new IOException(TOKEN, new Exception(EMAIL));
              }

              @Override
              public void close() throws IOException {
                bodyClosed = true;
                throw new IOException(REFRESH);
              }
            };
          }
        };
    assertSanitized(
        assertThrows(
            IOException.class,
            () -> CodexUsageClient.readResponse(connection, Collections.emptyMap())));
    assertTrue(connection.bodyClosed);
    assertTrue(connection.disconnected);
  }

  @Test
  public void preexistingCancellationPreventsTransportAndPayloadWork() {
    CodexUsageClient client =
        new CodexUsageClient(
            (url, headers) -> {
              fail("Cancelled request must not call transport");
              return null;
            });
    Thread.currentThread().interrupt();
    try {
      assertSanitized(
          assertThrows(InterruptedException.class, () -> client.fetch(credential(), NOW)));
      assertSanitized(
          assertThrows(
              InterruptedException.class,
              () -> CodexUsageClient.parsePayload(new JSONObject(), NOW)));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void transportInterruptionIsSanitizedAndRestoresFlag() {
    CodexUsageClient client =
        new CodexUsageClient(
            (url, headers) -> {
              throw new InterruptedException(TOKEN);
            });
    try {
      assertSanitized(
          assertThrows(InterruptedException.class, () -> client.fetch(credential(), NOW)));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void cancellationDuringTransportOverridesSuccessfulResponseOrFailure() throws Exception {
    for (boolean fail : new boolean[] {false, true}) {
      CodexUsageClient client =
          new CodexUsageClient(
              (url, headers) -> {
                Thread.currentThread().interrupt();
                if (fail) throw new IOException(TOKEN);
                return actualPayload();
              });
      try {
        assertSanitized(
            assertThrows(InterruptedException.class, () -> client.fetch(credential(), NOW)));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  public void cancelledHttpReadClosesStreamAndDisconnects() throws Exception {
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
      assertSanitized(
          assertThrows(
              InterruptedException.class,
              () -> CodexUsageClient.readResponse(connection, Collections.emptyMap())));
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(connection.bodyClosed);
      assertTrue(connection.disconnected);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void alreadyCancelledHttpDoesNotReadStatus() throws Exception {
    FakeConnection connection = new FakeConnection(200, "{}");
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          InterruptedException.class,
          () -> CodexUsageClient.readResponse(connection, Collections.emptyMap()));
      assertEquals(0, connection.statusReads);
      assertTrue(connection.disconnected);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  /** Entirely in memory: URL construction does not open a socket. */
  private static class FakeConnection extends HttpURLConnection {
    private final int status;
    private final byte[] body;
    long contentLength = -1;
    String retryAfter;
    int statusReads;
    boolean bodyRead;
    boolean errorRead;
    boolean locationRead;
    boolean bodyClosed;
    boolean disconnected;

    FakeConnection(int status, String body) throws Exception {
      this(URL, status, body);
    }

    FakeConnection(String address, int status, String body) throws Exception {
      super(new URL(address));
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
    public OutputStream getOutputStream() {
      throw new AssertionError("Read-only requests cannot write a body");
    }

    @Override
    public InputStream getErrorStream() {
      errorRead = true;
      throw new AssertionError("Error bodies must not be read");
    }

    @Override
    public String getHeaderField(String name) {
      if ("Retry-After".equalsIgnoreCase(name)) return retryAfter;
      if ("Location".equalsIgnoreCase(name)) {
        locationRead = true;
        return "https://example.invalid/" + TOKEN;
      }
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
