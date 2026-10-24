package local.aimonitor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public final class CostReport {
    public final Provider provider;
    public final String scope;
    public final LocalDate from;
    public final LocalDate through;
    public final Map<LocalDate, BigDecimal> daily;
    public final BigDecimal total;
    public final Instant fetchedAt;

    public CostReport(Provider provider, String scope, LocalDate from, LocalDate through,
                      Map<LocalDate, BigDecimal> daily) {
        this.provider = provider;
        this.scope = scope;
        this.from = from;
        this.through = through;
        TreeMap<LocalDate, BigDecimal> complete = new TreeMap<>();
        for (LocalDate day = from; !day.isAfter(through); day = day.plusDays(1)) {
            complete.put(day, daily.getOrDefault(day, BigDecimal.ZERO));
        }
        this.daily = Collections.unmodifiableMap(complete);
        this.total = complete.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        this.fetchedAt = Instant.now();
    }
}
