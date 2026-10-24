package local.aimonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Compact chart with a textual description for accessibility. */
public final class SpendChart extends View {
    private final CostReport report;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public SpendChart(Context context, CostReport report) {
        super(context);
        this.report = report;
        StringBuilder description = new StringBuilder("일별 API 비용, USD. ");
        for (Map.Entry<LocalDate, BigDecimal> row : report.daily.entrySet()) {
            description.append(row.getKey()).append(": ").append(row.getValue().toPlainString()).append(". ");
        }
        setContentDescription(description);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setMinimumHeight(dp(142));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float max = 0;
        for (BigDecimal value : report.daily.values()) max = Math.max(max, Math.abs(value.floatValue()));
        float baseline = getHeight() - dp(27);
        float height = baseline - dp(12);
        float band = getWidth() / (float) Math.max(1, report.daily.size());
        float barWidth = Math.min(dp(24), band * .6f);
        paint.setColor(0xffd9e1e9);
        canvas.drawLine(0, baseline, getWidth(), baseline, paint);
        int i = 0;
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("MM/dd");
        for (Map.Entry<LocalDate, BigDecimal> row : report.daily.entrySet()) {
            float x = band * (i + .5f);
            float amount = max == 0 ? 0 : Math.abs(row.getValue().floatValue()) / max;
            paint.setColor(row.getValue().signum() < 0 ? 0xff74889c : report.provider.color);
            if (amount > 0) canvas.drawRoundRect(new RectF(x - barWidth / 2,
                    baseline - height * amount, x + barWidth / 2, baseline), dp(3), dp(3), paint);
            if (i == 0 || i == report.daily.size() - 1 || i % Math.max(1, report.daily.size() / 4) == 0) {
                paint.setColor(0xff65788b);
                paint.setTextSize(dp(10));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(dateFormat.format(row.getKey()), x, getHeight() - dp(6), paint);
            }
            i++;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
