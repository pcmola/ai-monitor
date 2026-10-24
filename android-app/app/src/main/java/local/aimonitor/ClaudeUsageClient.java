package local.aimonitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Read-only subscription quota client. Never reads credentials from disk or refreshes tokens. */
public final class ClaudeUsageClient {
  private static final String USAGE_URL = "https://api.anthropic.com/api/oauth/usage";
  private static final int MAX_BYTES = 1024 * 1024;
  private static final long MAX_RETRY_AFTER_SECONDS = 24 * 60 * 60;
  private static final String INVALID_CREDENTIAL =
      "Invalid Claude OAuth credentials. Reimport credentials from Claude Code.";
  private static final String EXPIRED_CREDENTIAL =
      "Claude OAuth credentials have expired. Reimport credentials from Claude Code.";
  private static final String INVALID_PAYLOAD = "Invalid Claude usage response.";
  private static final String[][] WINDOWS = {
    {"five_hour", "5시간 세션"},
    {"seven_day", "주간 (전체 모델)"},
    {"seven_day_opus", "주간 (Opus)"},
    {"seven_day_sonnet", "주간 (Sonnet)"},
    {"seven_day_oauth_apps", "주간 (OAuth 앱)"}
  };

  public record Credential(String accessToken, long expiresAtMillis, String plan) {
    // The generated record implementation would disclose the access token.
    @Override
    public String toString() {
      return "Credential[redacted]";
    }
  }

  @FunctionalInterface
  public interface Transport {
    JSONObject get(String url, Map<String, String> headers) throws Exception;
  }

  /** Contains only a status and optional delay; -1 means Retry-After is unknown. */
  public static final class HttpStatusException extends IOException {
    private final int statusCode;
    private final long retryAfterSeconds;

    public HttpStatusException(int statusCode) {
      this(statusCode, -1);
    }

    public HttpStatusException(int statusCode, long retryAfterSeconds) {
      super(statusMessage(statusCode));
      this.statusCode = statusCode;
      this.retryAfterSeconds =
          retryAfterSeconds >= 0 ? Math.min(MAX_RETRY_AFTER_SECONDS, retryAfterSeconds) : -1;
    }

    public int statusCode() {
      return statusCode;
    }

    public long retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }

  private final Transport transport;

  public ClaudeUsageClient() {
    this(ClaudeUsageClient::httpsGet);
  }

  public ClaudeUsageClient(Transport transport) {
    this.transport = Objects.requireNonNull(transport);
  }

  /** Parses caller-supplied text only; refresh tokens and the original text are not retained. */
  public static Credential parseCredential(String text, Instant now) throws IOException {
    if (text == null
        || now == null
        || text.length() > MAX_BYTES
        || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new IOException(INVALID_CREDENTIAL);
    }
    String value = text.trim();
    if (value.isEmpty()) throw new IOException(INVALID_CREDENTIAL);
    if (!value.startsWith("{")) {
      validateToken(value);
      return new Credential(value, 0, null);
    }

    String token;
    String plan = null;
    long expiresAt = 0;
    boolean hasExpiry;
    try {
      JSONObject oauth = parseObject(value).getJSONObject("claudeAiOauth");
      Object accessToken = oauth.get("accessToken");
      if (!(accessToken instanceof String)) throw new IOException(INVALID_CREDENTIAL);
      token = (String) accessToken;
      hasExpiry = oauth.has("expiresAt");
      if (hasExpiry) {
        Object expiry = oauth.get("expiresAt");
        if (!(expiry instanceof Number)) throw new IOException(INVALID_CREDENTIAL);
        expiresAt = new BigDecimal(expiry.toString()).longValueExact();
      }
      if (oauth.has("subscriptionType") && !oauth.isNull("subscriptionType")) {
        Object subscription = oauth.get("subscriptionType");
        if (!(subscription instanceof String)) throw new IOException(INVALID_CREDENTIAL);
        plan = (String) subscription;
      }
    } catch (Exception ignored) {
      // Do not attach a parser exception: it can contain the input and refresh token.
      throw new IOException(INVALID_CREDENTIAL);
    }
    validateToken(token);
    // Explicit zero is expired; only an absent expiresAt means unknown expiry.
    if (hasExpiry && !Instant.ofEpochMilli(expiresAt).isAfter(now)) {
      throw new IOException(EXPIRED_CREDENTIAL);
    }
    return new Credential(token, expiresAt, plan);
  }

  public UsageReport fetch(Credential credential, Instant now) throws Exception {
    checkInterrupted();
    validateCredential(credential, now);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + credential.accessToken());
    headers.put("anthropic-beta", "oauth-2025-04-20");
    headers.put("Accept", "application/json");
    JSONObject payload;
    try {
      payload = transport.get(USAGE_URL, Collections.unmodifiableMap(headers));
      checkInterrupted();
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
      throw new InterruptedException("Claude usage request interrupted.");
    } catch (HttpStatusException status) {
      checkInterrupted();
      // Recreate even typed errors so a transport-supplied cause cannot leak secrets.
      throw new HttpStatusException(status.statusCode(), status.retryAfterSeconds());
    } catch (Exception ignored) {
      checkInterrupted();
      throw new IOException("Claude usage request failed. Check your connection and try again.");
    }
    return parsePayload(payload, credential, now);
  }

  public static UsageReport parsePayload(JSONObject payload, Credential credential, Instant now)
      throws IOException, InterruptedException {
    checkInterrupted();
    validateCredential(credential, now);
    if (payload == null) throw new IOException(INVALID_PAYLOAD);
    List<UsageReport.Window> windows = new ArrayList<>();
    try {
      for (String[] window : WINDOWS) {
        checkInterrupted();
        if (!payload.has(window[0]) || payload.isNull(window[0])) continue;
        JSONObject row = payload.getJSONObject(window[0]);
        Object utilization = row.get("utilization");
        if (!(utilization instanceof Number)) throw new IOException(INVALID_PAYLOAD);
        double percent = ((Number) utilization).doubleValue();
        BigDecimal exactPercent = new BigDecimal(utilization.toString());
        if (!Double.isFinite(percent)
            || exactPercent.signum() < 0
            || exactPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
          throw new IOException(INVALID_PAYLOAD);
        }
        Instant reset = null;
        if (row.has("resets_at") && !row.isNull("resets_at")) {
          Object resetsAt = row.get("resets_at");
          if (!(resetsAt instanceof String)) throw new IOException(INVALID_PAYLOAD);
          reset = Instant.parse((String) resetsAt);
        }
        windows.add(new UsageReport.Window(window[0], window[1], percent, reset));
      }
    } catch (InterruptedException ignored) {
      throw new InterruptedException("Claude usage request interrupted.");
    } catch (Exception ignored) {
      throw new IOException(INVALID_PAYLOAD);
    }
    if (windows.isEmpty()) {
      throw new IOException("Claude usage response contains no valid usage windows.");
    }
    return new UsageReport(
        credential.plan(), now, Collections.unmodifiableList(windows), Collections.emptyList());
  }

  private static void validateCredential(Credential credential, Instant now) throws IOException {
    if (credential == null || now == null) throw new IOException(INVALID_CREDENTIAL);
    validateToken(credential.accessToken());
    if (credential.expiresAtMillis() != 0
        && !Instant.ofEpochMilli(credential.expiresAtMillis()).isAfter(now)) {
      throw new IOException(EXPIRED_CREDENTIAL);
    }
  }

  private static void validateToken(String token) throws IOException {
    if (token == null
        || token.isEmpty()
        || token.length() > MAX_BYTES
        || token.startsWith("sk-ant-api")
        || token.startsWith("sk-ant-admin")) {
      throw new IOException(INVALID_CREDENTIAL);
    }
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      // Header-safe visible ASCII only, with JSON containers/quoted strings excluded.
      if (c <= ' ' || c >= 127 || c == '{' || c == '}' || c == '[' || c == ']' || c == '"') {
        throw new IOException(INVALID_CREDENTIAL);
      }
    }
  }

  private static JSONObject parseObject(String text) throws org.json.JSONException, IOException {
    JSONTokener parser = new JSONTokener(text);
    Object value = parser.nextValue();
    if (!(value instanceof JSONObject) || parser.nextClean() != 0) {
      throw new IOException("Invalid JSON object.");
    }
    return (JSONObject) value;
  }

  private static void checkInterrupted() throws InterruptedException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedException("Claude usage request interrupted.");
    }
  }

  private static String statusMessage(int status) {
    if (status == 401 || status == 403) {
      return "Claude OAuth access was rejected. Reimport credentials from Claude Code. HTTP "
          + status;
    }
    if (status == 429) return "Claude usage is rate limited. Wait before trying again. HTTP 429";
    return "Claude usage request failed. HTTP " + status;
  }

  private static JSONObject httpsGet(String address, Map<String, String> headers) throws Exception {
    checkInterrupted();
    if (!USAGE_URL.equals(address)) throw new IOException("Invalid Claude usage endpoint.");
    return readResponse((HttpURLConnection) new URL(address).openConnection(), headers);
  }

  // Separate from opening the connection so HTTP policy can be tested without any requests.
  static JSONObject readResponse(HttpURLConnection connection, Map<String, String> headers)
      throws Exception {
    try {
      checkInterrupted();
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(10_000);
      connection.setReadTimeout(20_000);
      connection.setUseCaches(false);
      for (Map.Entry<String, String> header : headers.entrySet()) {
        connection.setRequestProperty(header.getKey(), header.getValue());
      }
      int status = connection.getResponseCode();
      checkInterrupted();
      if (status != 200) {
        long delay =
            status == 429
                ? CodexUsageClient.retryAfter(
                    connection.getHeaderField("Retry-After"), Instant.now())
                : -1;
        throw new HttpStatusException(status, delay);
      }
      if (connection.getContentLengthLong() > MAX_BYTES) {
        throw new IOException("Claude usage response exceeds 1 MiB.");
      }
      try (InputStream stream = connection.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
          checkInterrupted();
          int count = stream.read(buffer);
          checkInterrupted();
          if (count == -1) break;
          if (count > MAX_BYTES - out.size()) {
            throw new IOException("Claude usage response exceeds 1 MiB.");
          }
          out.write(buffer, 0, count);
        }
        try {
          return parseObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
          throw new IOException(INVALID_PAYLOAD);
        }
      }
    } finally {
      connection.disconnect();
    }
  }
}
