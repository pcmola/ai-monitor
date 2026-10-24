package local.aimonitor;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class CostClientTest {
    private final LocalDate day = LocalDate.of(2026, 10, 18);
    private JSONObject json(String value) throws org.json.JSONException { return new JSONObject(value); }

    @Test public void anthropicCentsConvertedAndOtherWorkspacesExcluded() throws Exception {
        CostClient client = new CostClient((url, headers) -> {
            assertEquals("test-key", headers.get("x-api-key"));
            assertFalse(headers.containsKey("Authorization"));
            assertTrue(url.contains("group_by%5B%5D=workspace_id"));
            return json("""
                {"data":[{"starting_at":"2026-10-18T00:00:00Z","results":[
                {"workspace_id":"wrkspc_code","amount":"123.78912","currency":"USD"},
                {"workspace_id":"wrkspc_other","amount":"9999999","currency":"USD"},
                {"workspace_id":"wrkspc_code","amount":"0.01","currency":"USD"}
                ]}],"has_more":false,"next_page":null}
                """);
        });
        CostReport report = client.fetch(Provider.CLAUDE, "test-key", "wrkspc_code", day, day);
        assertEquals(0, new BigDecimal("1.2379912").compareTo(report.total));
    }

    @Test public void openaiAmountsAlreadyDollarsAndProjectIsFiltered() throws Exception {
        CostClient client = new CostClient((url, headers) -> {
            assertEquals("Bearer test-key", headers.get("Authorization"));
            assertTrue(url.contains("project_ids%5B%5D=proj_code"));
            return json("""
                {"data":[{"start_time":1792281600,"results":[
                {"project_id":"proj_code","amount":{"value":0.06,"currency":"usd"}},
                {"project_id":"proj_other","amount":{"value":8000,"currency":"usd"}},
                {"project_id":"proj_code","amount":{"value":-0.01,"currency":"usd"}}
                ]}],"has_more":false}
                """);
        });
        CostReport report = client.fetch(Provider.CODEX, "test-key", "proj_code", day, day);
        assertEquals(0, new BigDecimal("0.05").compareTo(report.total));
    }

    @Test public void paginationAccumulatesAndEncodesCursorWithoutLeakingKey() throws Exception {
        AtomicInteger page = new AtomicInteger();
        List<String> urls = new ArrayList<>();
        CostClient client = new CostClient((url, headers) -> {
            urls.add(url);
            assertFalse(url.contains("sensitive-secret"));
            if (page.getAndIncrement() == 0) return json("""
                {"data":[{"starting_at":"2026-10-18T00:00:00Z","results":[
                {"workspace_id":"wrkspc_code","amount":"100","currency":"USD"}]}],
                "has_more":true,"next_page":"token+/="}
                """);
            return json("""
                {"data":[{"starting_at":"2026-10-18T00:00:00Z","results":[
                {"workspace_id":"wrkspc_code","amount":"200","currency":"USD"}]}],
                "has_more":false}
                """);
        });
        CostReport report = client.fetch(Provider.CLAUDE, "sensitive-secret", "wrkspc_code", day, day);
        assertEquals(2, urls.size());
        assertTrue(urls.get(1).endsWith("page=token%2B%2F%3D"));
        assertEquals(0, new BigDecimal("3").compareTo(report.total));
    }

    @Test public void paginationFailureDoesNotReturnPartialTotal() {
        AtomicInteger page = new AtomicInteger();
        CostClient client = new CostClient((url, headers) -> {
            if (page.getAndIncrement() > 0) throw new IOException("network");
            return json("""
                {"data":[{"starting_at":"2026-10-18T00:00:00Z","results":[
                {"workspace_id":"code","amount":"100","currency":"USD"}]}],
                "has_more":true,"next_page":"second"}
                """);
        });
        assertThrows(IOException.class, () -> client.fetch(Provider.CLAUDE, "key", "code", day, day));
    }

    @Test public void repeatedCursorFailsInsteadOfDoubleCounting() {
        CostClient client = new CostClient((url, headers) ->
                json("{\"data\":[],\"has_more\":true,\"next_page\":\"same\"}"));
        assertThrows(IOException.class, () -> client.fetch(Provider.CLAUDE, "key", "code", day, day));
    }

    @Test public void emptySuccessfulResponseIsZeroAndFillsAllRequestedDays() throws Exception {
        CostClient client = new CostClient((url, headers) -> json("{\"data\":[],\"has_more\":false}"));
        CostReport report = client.fetch(Provider.CODEX, "key", "proj_code", day.minusDays(6), day);
        assertEquals(7, report.daily.size());
        assertEquals(BigDecimal.ZERO, report.total);
    }

    @Test public void cannotUseOrganizationTotalsWithoutScope() {
        CostClient client = new CostClient((url, headers) -> json("""
                {"data":[{"starting_at":"2026-10-18T00:00:00Z","results":[
                {"amount":"100","currency":"USD"}]}],"has_more":false}
                """));
        assertThrows(IOException.class, () -> client.fetch(Provider.CLAUDE, "key", "code", day, day));
    }

    @Test public void defaultWorkspaceDoesNotPolluteNamedWorkspace() throws Exception {
        CostClient client = new CostClient((url, headers) -> json("""
                {"data":[{"starting_at":"2026-10-18T00:00:00Z","results":[
                {"workspace_id":null,"amount":"99000","currency":"USD"},
                {"workspace_id":"code","amount":"100","currency":"USD"}
                ]}],"has_more":false}
                """));
        assertEquals(0, BigDecimal.ONE.compareTo(
                client.fetch(Provider.CLAUDE, "key", "code", day, day).total));
    }

    @Test public void unknownCurrencyIsNotSilentlyAddedToUsd() {
        CostClient client = new CostClient((url, headers) -> json("""
                {"data":[{"starting_at":"2026-10-18T00:00:00Z","results":[
                {"workspace_id":"code","amount":"100","currency":"KRW"}]}],"has_more":false}
                """));
        assertThrows(IOException.class, () -> client.fetch(Provider.CLAUDE, "key", "code", day, day));
    }

    @Test public void missingCredentialsNeverSendRequest() {
        CostClient client = new CostClient((url, headers) -> { fail("request must not be sent"); return null; });
        assertThrows(IOException.class, () -> client.fetch(Provider.CODEX, "", "code", day, day));
        assertThrows(IOException.class, () -> client.fetch(Provider.CODEX, "key", "", day, day));
    }

    @Test public void timesCoverUtcDaysWithExclusiveEnd() throws Exception {
        CostClient client = new CostClient((url, headers) -> {
            assertTrue(url.contains("start_time=" + day.atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC)));
            assertTrue(url.contains("end_time=" + day.plusDays(1).atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC)));
            return json("{\"data\":[],\"has_more\":false}");
        });
        client.fetch(Provider.CODEX, "key", "code", day, day);
    }
}
