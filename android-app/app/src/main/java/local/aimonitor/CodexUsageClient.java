package local.aimonitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Read-only ChatGPT subscription quotas. No credential files, token refresh, or API keys. */
public final class CodexUsageClient {
  private static final String USAGE_URL = "https://chatgpt.com/backend-api/wham/usage";
  private static final int MAX_BYTES = 1024 * 1024;
  private static final long MAX_RETRY_AFTER_SECONDS = 24 * 60 * 60;
  private static final String INVALID_CREDENTIAL =
      "Invalid Codex OAuth credentials. Reimport credentials from Codex.";
  private static final String INVALID_PAYLOAD = "Invalid Codex usage response.";
  private static final String REQUEST_FAILED =
      "Codex usage request failed. Check your connection and try again.";
  private static final String INTERRUPTED = "Codex usage request interrupted.";

  public record Credential(String accessToken, String accountId) {
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

  private static final class BodyTooLargeException extends IOException {}

  private final Transport transport;

  public CodexUsageClient() {
    this(CodexUsageClient::httpsGet);
  }

  public CodexUsageClient(Transport transport) {
    this.transport = Objects.requireNonNull(transport);
  }

  /**
   * Parses supplied text only. Unknown expiry is allowed; tokens are never decoded or refreshed.
   */
  public static Credential parseCredential(String text, Instant now) throws IOException {
    if (text == null
        || now == null
        || text.length() > MAX_BYTES
        || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new IOException(INVALID_CREDENTIAL);
    }
    String value = text.trim();
    Credential credential;
    if (value.startsWith("{")) {
      try {
        JSONObject object = parseObject(value);
        boolean full = object.has("tokens");
        JSONObject source = full ? object.getJSONObject("tokens") : object;
        Object token = source.get(full ? "access_token" : "accessToken");
        if (!(token instanceof String)) throw new IOException(INVALID_CREDENTIAL);
        String accountKey = full ? "account_id" : "accountId";
        String account = null;
        if (source.has(accountKey) && !source.isNull(accountKey)) {
          Object accountValue = source.get(accountKey);
          if (!(accountValue instanceof String)) throw new IOException(INVALID_CREDENTIAL);
          account = (String) accountValue;
        }
        // Only these two allowlisted fields leave this method.
        credential = new Credential((String) token, account);
      } catch (Exception ignored) {
        throw new IOException(INVALID_CREDENTIAL);
      }
    } else {
      // Do not trim away control characters or whitespace from a bare header value.
      credential = new Credential(text, null);
    }
    validateCredential(credential, now);
    return credential;
  }

  public UsageReport fetch(Credential credential, Instant now) throws Exception {
    checkInterrupted();
    validateCredential(credential, now);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + credential.accessToken());
    if (credential.accountId() != null) {
      headers.put("ChatGPT-Account-Id", credential.accountId());
    }
    headers.put("User-Agent", "CodeSpend/4.0 Android");
    headers.put("Accept", "application/json");
    JSONObject payload;
    try {
      payload = transport.get(USAGE_URL, Collections.unmodifiableMap(headers));
      checkInterrupted();
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
      throw new InterruptedException(INTERRUPTED);
    } catch (HttpStatusException status) {
      checkInterrupted();
      // A custom transport may attach sensitive causes or suppressed exceptions.
      throw new HttpStatusException(status.statusCode(), status.retryAfterSeconds());
    } catch (Exception ignored) {
      checkInterrupted();
      throw new IOException(REQUEST_FAILED);
    }
    return parsePayload(payload, now);
  }

  public static UsageReport parsePayload(JSONObject payload, Instant now)
      throws IOException, InterruptedException {
    checkInterrupted();
    if (payload == null || now == null) throw new IOException(INVALID_PAYLOAD);
    List<UsageReport.Window> windows = new ArrayList<>();
    String plan;
    try {
      Object planValue = payload.opt("plan_type");
      plan = planValue instanceof String ? (String) planValue : null;
      JSONObject limits = payload.getJSONObject("rate_limit");
      for (String key : new String[] {"primary_window", "secondary_window"}) {
        checkInterrupted();
        if (!limits.has(key) || limits.isNull(key)) continue;
        JSONObject row = limits.getJSONObject(key);
        Object used = row.get("used_percent");
        if (!(used instanceof Number)) throw new IOException(INVALID_PAYLOAD);
        double percent = ((Number) used).doubleValue();
        BigDecimal exact = new BigDecimal(used.toString());
        if (!Double.isFinite(percent)
            || exact.signum() < 0
            || exact.compareTo(BigDecimal.valueOf(100)) > 0) {
          throw new IOException(INVALID_PAYLOAD);
        }
        int seconds = Math.toIntExact(integer(row.get("limit_window_seconds")));
        if (seconds <= 0) throw new IOException(INVALID_PAYLOAD);
        Instant reset = null;
        if (row.has("reset_at")) {
          reset = Instant.ofEpochSecond(nonnegativeInteger(row.get("reset_at")));
        } else if (row.has("reset_after_seconds")) {
          reset = now.plusSeconds(nonnegativeInteger(row.get("reset_after_seconds")));
        }
        windows.add(new UsageReport.Window(key, windowLabel(seconds), percent, reset));
      }
    } catch (InterruptedException ignored) {
      throw new InterruptedException(INTERRUPTED);
    } catch (Exception ignored) {
      throw new IOException(INVALID_PAYLOAD);
    }
    if (windows.isEmpty()) {
      throw new IOException("Codex usage response contains no valid usage windows.");
    }
    checkInterrupted();
    return new UsageReport(plan, now, windows, Collections.emptyList());
  }

  private static long integer(Object value) throws IOException {
    if (!(value instanceof Number)) throw new IOException(INVALID_PAYLOAD);
    return new BigDecimal(value.toString()).longValueExact();
  }

  private static long nonnegativeInteger(Object value) throws IOException {
    long number = integer(value);
    if (number < 0) throw new IOException(INVALID_PAYLOAD);
    return number;
  }

  private static String windowLabel(int seconds) {
    if (seconds == 18000) return "5시간 세션";
    if (seconds == 604800) return "주간";
    if (seconds % 3600 == 0) return (seconds / 3600) + "시간";
    if (seconds % 60 == 0) return (seconds / 60) + "분";
    return seconds + "초";
  }

  private static void validateCredential(Credential credential, Instant now) throws IOException {
    if (credential == null || now == null) throw new IOException(INVALID_CREDENTIAL);
    validateHeaderValue(credential.accessToken(), MAX_BYTES);
    if (credential.accessToken().startsWith("sk-")) throw new IOException(INVALID_CREDENTIAL);
    if (credential.accountId() != null) validateHeaderValue(credential.accountId(), 256);
  }

  private static void validateHeaderValue(String value, int maximum) throws IOException {
    if (value == null || value.isEmpty() || value.length() > maximum) {
      throw new IOException(INVALID_CREDENTIAL);
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c <= ' ' || c >= 127 || c == '{' || c == '}' || c == '[' || c == ']' || c == '"'
          || c == '\\') {
        throw new IOException(INVALID_CREDENTIAL);
      }
    }
  }

  private static JSONObject parseObject(String text) throws Exception {
    // Bound nesting before org.json recurses, including ignored credential fields.
    int depth = 0;
    boolean quoted = false;
    boolean escaped = false;
    char quote = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == quote) quoted = false;
      } else if (c == '"' || c == '\'') {
        quoted = true;
        quote = c;
      } else if ((c == '{' || c == '[') && ++depth > 64) {
        throw new IOException(INVALID_PAYLOAD);
      } else if (c == '}' || c == ']') {
        depth--;
      }
    }
    JSONTokener parser = new JSONTokener(text);
    Object value = parser.nextValue();
    if (!(value instanceof JSONObject) || parser.nextClean() != 0) {
      throw new IOException(INVALID_PAYLOAD);
    }
    return (JSONObject) value;
  }

  private static void checkInterrupted() throws InterruptedException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedException(INTERRUPTED);
  }

  private static String statusMessage(int status) {
    if (status == 401 || status == 403) {
      return "Codex OAuth access was rejected. Reimport credentials from Codex. HTTP " + status;
    }
    if (status == 429) return "Codex usage is rate limited. Wait before trying again. HTTP 429";
    return "Codex usage request failed. HTTP " + status;
  }

  private static JSONObject httpsGet(String address, Map<String, String> headers) throws Exception {
    checkInterrupted();
    if (!USAGE_URL.equals(address)) throw new IOException("Invalid Codex usage endpoint.");
    // HTTPS defaults provide certificate/hostname validation; no TLS settings are overridden.
    return readResponse((HttpURLConnection) new URL(address).openConnection(), headers);
  }

  /** Package-visible seam for socket-free HTTP policy tests. Validates before sending headers. */
  static JSONObject readResponse(HttpURLConnection connection, Map<String, String> headers)
      throws Exception {
    try {
      checkInterrupted();
      if (connection == null || !USAGE_URL.equals(connection.getURL().toExternalForm())) {
        throw new IOException(REQUEST_FAILED);
      }
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod("GET");
      connection.setDoOutput(false);
      connection.setDoInput(true);
      connection.setAllowUserInteraction(false);
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
                ? retryAfter(connection.getHeaderField("Retry-After"), Instant.now())
                : -1;
        throw new HttpStatusException(status, delay);
      }
      if (connection.getContentLengthLong() > MAX_BYTES) throw new BodyTooLargeException();
      JSONObject result;
      try (InputStream stream = connection.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
          checkInterrupted();
          int count = stream.read(buffer);
          checkInterrupted();
          if (count == -1) break;
          if (count > MAX_BYTES - out.size()) throw new BodyTooLargeException();
          out.write(buffer, 0, count);
        }
        result = parseObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
      }
      checkInterrupted();
      return result;
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
      throw new InterruptedException(INTERRUPTED);
    } catch (HttpStatusException status) {
      checkInterrupted();
      throw new HttpStatusException(status.statusCode(), status.retryAfterSeconds());
    } catch (BodyTooLargeException ignored) {
      checkInterrupted();
      throw new IOException("Codex usage response exceeds 1 MiB.");
    } catch (Exception ignored) {
      checkInterrupted();
      throw new IOException(REQUEST_FAILED);
    } finally {
      if (connection != null) {
        try {
          connection.disconnect();
        } catch (Exception ignored) {
          // Cleanup errors must not disclose URLs, response bodies, or header values.
        }
      }
    }
  }

  // Bounded advice only; the client never sleeps or retries. Clock is explicit for offline tests.
  static long retryAfter(String value, Instant now) {
    if (value == null || now == null || value.length() > 64) return -1;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < ' ' || c >= 127) return -1;
    }
    String text = value.trim();
    if (text.isEmpty()) return -1;
    boolean numeric = true;
    long seconds = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        numeric = false;
        break;
      }
      // Saturate during parsing, so even an overflowing decimal stays bounded.
      seconds = Math.min(MAX_RETRY_AFTER_SECONDS, seconds * 10 + c - '0');
    }
    if (numeric) return seconds;
    try {
      Instant date =
          ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC))
              .toInstant();
      Duration delay = Duration.between(now, date);
      if (delay.isNegative() || delay.isZero()) return 0;
      long rounded = delay.getSeconds() + (delay.getNano() == 0 ? 0 : 1);
      return Math.min(MAX_RETRY_AFTER_SECONDS, rounded);
    } catch (Exception ignored) {
      return -1;
    }
  }
}
