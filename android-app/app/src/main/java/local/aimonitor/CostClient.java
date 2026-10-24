package local.aimonitor;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Read-only reporting endpoints; never sends an inference or payment request. */
public final class CostClient {
    public interface Transport {
        JSONObject get(String url, Map<String, String> headers) throws Exception;
    }

    private final Transport transport;
    public CostClient() { this(CostClient::httpsGet); }
    public CostClient(Transport transport) { this.transport = transport; }

    public CostReport fetch(Provider provider, String apiKey, String scope,
                            LocalDate from, LocalDate through) throws Exception {
        if (apiKey.trim().isEmpty()) throw new IOException("관리자 API 키를 설정하세요.");
        if (scope.trim().isEmpty()) throw new IOException("전용 프로젝트 / 워크스페이스 ID를 설정하세요.");
        if (from.isAfter(through) || java.time.temporal.ChronoUnit.DAYS.between(from, through) >= 31) {
            throw new IOException("조회 기간은 1~31일이어야 합니다.");
        }
        if (apiKey.indexOf('\n') >= 0 || apiKey.indexOf('\r') >= 0) {
            throw new IOException("API 키에 줄바꿈이 들어 있습니다.");
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        String base;
        if (provider == Provider.CLAUDE) {
            headers.put("x-api-key", apiKey);
            headers.put("anthropic-version", "2023-06-01");
            // This endpoint has no workspace filter. Group, then select the exact
            // workspace locally; don't present organization spend as Claude Code.
            base = "https://api.anthropic.com/v1/organizations/cost_report"
                    + "?starting_at=" + encode(from + "T00:00:00Z")
                    + "&ending_at=" + encode(through.plusDays(1) + "T00:00:00Z")
                    + "&bucket_width=1d&group_by%5B%5D=workspace_id&limit=31";
        } else {
            headers.put("Authorization", "Bearer " + apiKey);
            base = "https://api.openai.com/v1/organization/costs"
                    + "?start_time=" + from.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
                    + "&end_time=" + through.plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
                    + "&bucket_width=1d&group_by%5B%5D=project_id&project_ids%5B%5D="
                    + encode(scope) + "&limit=31";
        }
        Map<LocalDate, BigDecimal> daily = new HashMap<>();
        Set<String> seen = new HashSet<>();
        String page = "";
        for (int n = 0; n < 120; n++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            JSONObject payload = transport.get(base + (page.isEmpty() ? "" : "&page=" + encode(page)), headers);
            append(provider, scope, payload, daily);
            if (!payload.getBoolean("has_more")) {
                return new CostReport(provider, scope, from, through, daily);
            }
            page = payload.optString("next_page", "");
            if (page.isEmpty() || page.equals("null") || !seen.add(page)) {
                throw new IOException("비용 응답의 다음 페이지 정보가 올바르지 않습니다.");
            }
        }
        throw new IOException("비용 페이지가 너무 많습니다. 조회 기간을 줄이세요.");
    }

    static void append(Provider provider, String scope, JSONObject payload,
                       Map<LocalDate, BigDecimal> daily) throws Exception {
        JSONArray buckets = payload.getJSONArray("data");
        for (int i = 0; i < buckets.length(); i++) {
            JSONObject bucket = buckets.getJSONObject(i);
            LocalDate day = provider == Provider.CLAUDE
                    ? Instant.parse(bucket.getString("starting_at")).atOffset(ZoneOffset.UTC).toLocalDate()
                    : Instant.ofEpochSecond(bucket.getLong("start_time")).atOffset(ZoneOffset.UTC).toLocalDate();
            JSONArray results = bucket.getJSONArray("results");
            for (int j = 0; j < results.length(); j++) {
                JSONObject row = results.getJSONObject(j);
                String scopeField = provider == Provider.CLAUDE ? "workspace_id" : "project_id";
                // If grouping was ignored, there is no safe way to attribute this row.
                if (!row.has(scopeField)) {
                    throw new IOException("전용 공간별 비용을 구분할 수 없는 응답입니다.");
                }
                // Null groups are default/unattributed costs, never our named scope.
                if (row.isNull(scopeField)) continue;
                if (!scope.equals(row.getString(scopeField))) continue;
                BigDecimal amount;
                String currency;
                if (provider == Provider.CLAUDE) {
                    currency = row.getString("currency");
                    // Anthropic reports decimal cents; OpenAI reports USD.
                    amount = new BigDecimal(row.getString("amount")).movePointLeft(2);
                } else {
                    JSONObject money = row.getJSONObject("amount");
                    currency = money.getString("currency");
                    amount = new BigDecimal(money.get("value").toString());
                }
                if (!"USD".equalsIgnoreCase(currency)) {
                    throw new IOException("USD 이외의 통화 응답은 합산하지 않습니다.");
                }
                daily.merge(day, amount, BigDecimal::add);
            }
        }
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static JSONObject httpsGet(String address, Map<String, String> headers) throws Exception {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol())
                || !("api.anthropic.com".equals(url.getHost()) || "api.openai.com".equals(url.getHost()))) {
            throw new IOException("공식 비용 API 주소가 아닙니다.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false); // Never forward credentials to redirect targets.
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        try {
            int code = connection.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("관리자 API 키와 비용 조회 권한을 확인하세요. HTTP " + code);
            if (code == 429) throw new IOException("조회 요청이 많습니다. 잠시 후 새로고침하세요. HTTP 429");
            if (code != 200) throw new IOException("비용 조회에 실패했습니다. HTTP " + code);
            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (out.size() + count > 8 * 1024 * 1024) throw new IOException("비용 응답이 너무 큽니다.");
                    out.write(buffer, 0, count);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            connection.disconnect();
        }
    }
}
