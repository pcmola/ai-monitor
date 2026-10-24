package local.aimonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int INK = 0xff142638, MUTED = 0xff61758b, BG = 0xfff2f5f8;
    private static final int LINE = 0xffdce4ec, GREEN = 0xff0b826d, WHITE = Color.WHITE;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Future<?>> pending = new ArrayList<>();
    private final Map<Provider, CostReport> reports = new EnumMap<>(Provider.class);
    private final Map<Provider, String> errors = new EnumMap<>(Provider.class);
    private final Map<Provider, LinearLayout> panels = new EnumMap<>(Provider.class);
    private final Map<Provider, Boolean> loading = new EnumMap<>(Provider.class);
    private CredentialStore credentials;
    private SharedPreferences settings;
    private LinearLayout content;
    private TextView summary;
    private String mode = "account";
    private int days = 7;
    private int generation = 0;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        // Cost screens and credential dialogs must not appear in recent-app snapshots.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        credentials = new CredentialStore(this);
        settings = getSharedPreferences("code-spend-ui", MODE_PRIVATE);
        mode = settings.getString("mode", "account");
        days = settings.getInt("days", 7) == 30 ? 30 : 7;
        if (state != null) {
            mode = state.getString("mode", mode);
            days = state.getInt("days", days);
        }
        buildUi();
        if ("api".equals(mode)) refresh();
    }

    private void buildUi() {
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);
        // API 35 enforces edge-to-edge. Keep both controls and data out of system bars.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(16), dp(14), dp(16));
        header.setBackgroundColor(INK);
        LinearLayout heading = vertical();
        heading.addView(text("CODE SPEND", 12, 0xff8fcdbf, true));
        heading.addView(text("코딩 비용", 25, WHITE, true));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button about = action("안내", WHITE, INK);
        about.setOnClickListener(v -> showAbout());
        header.addView(about);
        root.addView(header);

        LinearLayout tabs = horizontal();
        tabs.setPadding(dp(16), dp(14), dp(16), 0);
        tabs.addView(tab("구독 계정", "account"), weighted());
        tabs.addView(tab("API 비용", "api"), weighted());
        root.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = vertical();
        content.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if ("api".equals(mode)) buildApi(); else buildAccounts();
    }

    private Button tab(String title, String value) {
        boolean active = mode.equals(value);
        Button button = action(title, active ? WHITE : MUTED, active ? INK : WHITE);
        button.setOnClickListener(v -> {
            if (!mode.equals(value)) {
                cancelRequests();
                mode = value;
                settings.edit().putString("mode", value).apply();
                buildUi();
                if ("api".equals(mode)) refresh();
            }
        });
        return button;
    }

    private void buildAccounts() {
        content.addView(text("계정별 비용과 사용 현황", 20, INK, true));
        content.addView(paragraph("휴대폰에서 각 서비스에 로그인해 구독 한도, 추가 사용과 결제 정보를 확인하세요."));
        for (Provider provider : Provider.values()) {
            LinearLayout card = card(provider);
            TextView message = text("공식 계정에서 확인", 22, INK, true);
            message.setPadding(0, dp(20), 0, dp(8));
            card.addView(message);
            card.addView(paragraph(provider == Provider.CLAUDE
                    ? "Pro·Max 등 구독 사용량과 추가 사용 비용"
                    : "ChatGPT 구독에 포함된 Codex 사용량과 크레딧"));
            Button account = action("사용량·크레딧 보기  ↗", WHITE, provider.color);
            account.setOnClickListener(v -> openOfficial(provider.accountUrl, provider.color));
            card.addView(account, fullButton());
            Button billing = action("구독·결제 보기  ↗", provider.color, WHITE);
            billing.setOnClickListener(v -> openOfficial(provider.billingUrl(), provider.color));
            card.addView(billing, fullButton());
            Button api = action("API 청구 내역  ↗", provider.color, WHITE);
            api.setOnClickListener(v -> openOfficial(provider.apiDashboardUrl, provider.color));
            card.addView(api, fullButton());
            content.addView(card);
        }
        content.addView(paragraph("로그인은 공식 브라우저 탭에서 진행됩니다. "
                + "구독료와 사용 한도는 API 청구 비용에 합산되지 않습니다."));
    }

    private void buildApi() {
        panels.clear();
        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("전용 공간의 API 비용", 20, INK, true), weighted());
        Button refresh = action("새로고침", GREEN, WHITE);
        refresh.setOnClickListener(v -> refresh());
        heading.addView(refresh);
        content.addView(heading);
        content.addView(paragraph("Claude Code·Codex만 사용하는 공간을 연결하세요. 금액은 USD, 날짜는 UTC 기준입니다."));
        LinearLayout ranges = horizontal();
        for (int value : new int[]{7, 30}) {
            Button period = action("최근 " + value + "일", days == value ? WHITE : INK,
                    days == value ? GREEN : WHITE);
            period.setOnClickListener(v -> {
                if (days != value) {
                    cancelRequests();
                    days = value;
                    settings.edit().putInt("days", value).apply();
                    reports.clear();
                    errors.clear();
                    buildUi();
                    refresh();
                }
            });
            ranges.addView(period, weighted());
        }
        content.addView(ranges);
        summary = text("", 13, MUTED, false);
        summary.setPadding(0, dp(12), 0, dp(4));
        content.addView(summary);
        for (Provider provider : Provider.values()) {
            LinearLayout panel = card(provider);
            LinearLayout body = vertical();
            panel.addView(body);
            panels.put(provider, body);
            content.addView(panel);
            renderProvider(provider);
        }
        content.addView(paragraph("오늘 비용은 부분 집계이며 이후 수정될 수 있습니다. "
                + "결제 계정의 최종 청구 내역이 기준입니다."));
        updateSummary();
    }

    private void renderProvider(Provider provider) {
        LinearLayout body = panels.get(provider);
        if (body == null) return;
        body.removeAllViews();
        if (!credentials.configured(provider)) {
            TextView label = text("아직 연결하지 않았습니다", 19, INK, true);
            label.setPadding(0, dp(18), 0, dp(6));
            body.addView(label);
            body.addView(paragraph("관리자 API 키와 " + provider.scopeLabel + "가 필요합니다."));
        } else {
            if (Boolean.TRUE.equals(loading.get(provider))) {
                ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                progress.setIndeterminate(true);
                body.addView(progress, new LinearLayout.LayoutParams(-1, dp(16)));
                body.addView(paragraph("공식 비용 API에서 조회 중…"));
            }
            String error = errors.get(provider);
            if (error != null) {
                TextView warning = paragraph(error);
                warning.setTextColor(0xffa7352a);
                body.addView(warning);
            }
            CostReport report = reports.get(provider);
            if (report != null) {
                TextView amount = text(usd(report.total), 34, INK, true);
                amount.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                amount.setPadding(0, dp(14), 0, dp(4));
                body.addView(amount);
                body.addView(text(report.from + " ~ " + report.through + " · USD", 12, MUTED, false));
                body.addView(new SpendChart(this, report), new LinearLayout.LayoutParams(-1, dp(148)));
                body.addView(paragraph("전용 공간: " + report.scope));
                String at = DateTimeFormatter.ofPattern("MM/dd HH:mm")
                        .withZone(ZoneId.systemDefault()).format(report.fetchedAt);
                body.addView(text((error != null ? "이전 조회값 · " : "조회 완료 · ") + at, 12, MUTED, false));
                Button daily = action("일별 금액 보기", provider.color, WHITE);
                daily.setOnClickListener(v -> showDaily(report));
                body.addView(daily, fullButton());
            } else if (error != null) {
                body.addView(paragraph("조회하지 못한 금액은 0원으로 표시하지 않습니다."));
            }
        }
        Button connect = action(credentials.configured(provider) ? "연결 설정" : "API 연결",
                WHITE, provider.color);
        connect.setOnClickListener(v -> configure(provider));
        body.addView(connect, fullButton());
    }

    private void refresh() {
        if (!"api".equals(mode)) return;
        cancelRequests();
        final int request = generation;
        LocalDate through = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = through.minusDays(days - 1L);
        for (Provider provider : Provider.values()) {
            if (!credentials.configured(provider)) continue;
            loading.put(provider, true);
            errors.remove(provider);
            renderProvider(provider);
            pending.add(executor.submit(() -> {
                CostReport report = null;
                String error = null;
                try {
                    CredentialStore.Credentials account = credentials.load(provider);
                    if (account == null) throw new java.io.IOException("계정을 다시 연결하세요.");
                    report = new CostClient().fetch(provider, account.key(), account.scope(), from, through);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception failure) {
                    // Do not expose HTTP bodies, tokens or URLs in errors.
                    error = failure instanceof java.io.IOException ? failure.getMessage()
                            : "인증 정보 또는 비용 응답을 읽지 못했습니다. 연결 설정을 확인하세요.";
                }
                final CostReport result = report;
                final String message = error;
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || request != generation || !"api".equals(mode)) return;
                    loading.put(provider, false);
                    if (result != null) reports.put(provider, result);
                    if (message != null) errors.put(provider, message);
                    renderProvider(provider);
                    updateSummary();
                });
            }));
        }
        updateSummary();
    }

    private void updateSummary() {
        if (summary == null) return;
        int connected = 0, complete = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Provider provider : Provider.values()) {
            if (!credentials.configured(provider)) continue;
            connected++;
            CostReport report = reports.get(provider);
            if (report != null && !errors.containsKey(provider)
                    && !Boolean.TRUE.equals(loading.get(provider))) {
                complete++;
                total = total.add(report.total);
            }
        }
        if (connected == 0) summary.setText("API 키가 없으면 ‘구독 계정’에서 공식 화면을 이용하세요.");
        else if (complete != connected) summary.setText("연결 " + connected + "개 · 조회 완료 " + complete + "개");
        else summary.setText("연결된 전용 공간 합계  " + usd(total) + " USD");
    }

    private void configure(Provider provider) {
        CredentialStore.Credentials previous;
        try { previous = credentials.load(provider); }
        catch (Exception error) { previous = null; }
        final CredentialStore.Credentials old = previous;
        LinearLayout form = vertical();
        form.setPadding(dp(24), dp(8), dp(24), 0);
        form.addView(paragraph("API 방식으로 사용하는 경우에만 연결하세요. "
                + "개인 Pro·Max·Plus 구독은 ‘구독 계정’에서 확인합니다."));
        form.addView(text("관리자 API 키", 13, INK, true));
        EditText key = input(old == null ? "" : "저장된 키 유지 (새 키 입력 시 교체)", true);
        form.addView(key);
        form.addView(text(provider.scopeLabel, 13, INK, true));
        EditText scope = input(provider == Provider.CLAUDE ? "wrkspc_..." : "proj_...", false);
        if (old != null) scope.setText(old.scope());
        form.addView(scope);
        CheckBox dedicated = new CheckBox(this);
        dedicated.setText(provider.title + "만 사용하는 전용 공간입니다.");
        dedicated.setChecked(old != null);
        form.addView(dedicated);
        Button docs = action("관리자 키 페이지 열기  ↗", provider.color, WHITE);
        docs.setOnClickListener(v -> openOfficial(provider.keyManagementUrl, provider.color));
        form.addView(docs);
        TextView error = text("", 12, 0xffa7352a, false);
        form.addView(error);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(provider.title + " API 연결")
                .setView(scroll)
                .setNegativeButton("취소", null)
                .setNeutralButton("연결 해제", (d, w) -> {
                    cancelRequests();
                    credentials.remove(provider);
                    reports.remove(provider);
                    errors.remove(provider);
                    buildUi();
                    refresh();
                })
                .setPositiveButton("저장 후 조회", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String apiKey = key.getText().toString().trim();
            String scopeId = scope.getText().toString().trim();
            if (apiKey.isEmpty() && old != null) apiKey = old.key();
            if (apiKey.isEmpty() || scopeId.isEmpty() || !dedicated.isChecked()) {
                error.setText("관리자 키, 전용 공간 ID와 확인 체크가 필요합니다.");
                return;
            }
            if (!scopeId.matches("[A-Za-z0-9_-]+") || apiKey.matches("(?s).*\\s.*")) {
                error.setText("키와 공간 ID에 공백이나 줄바꿈을 넣지 마세요.");
                return;
            }
            try {
                credentials.save(provider, apiKey, scopeId);
                cancelRequests();
                reports.remove(provider);
                errors.remove(provider);
                dialog.dismiss();
                buildUi();
                refresh();
            } catch (Exception failure) {
                error.setText("인증 정보를 안전하게 저장하지 못했습니다. 다시 시도하세요.");
            }
        }));
        dialog.show();
        // Match activity snapshot protection for sensitive dialogs.
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void showDaily(CostReport report) {
        StringBuilder lines = new StringBuilder("USD · UTC 기준\n\n");
        List<LocalDate> dates = new ArrayList<>(report.daily.keySet());
        java.util.Collections.reverse(dates);
        for (LocalDate day : dates) lines.append(day).append("    ").append(usd(report.daily.get(day))).append("\n");
        new AlertDialog.Builder(this).setTitle(report.provider.title + " 일별 비용")
                .setMessage(lines.toString()).setPositiveButton("닫기", null).show();
    }

    private void openOfficial(String address, int color) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(address));
        // The platform browser retains its own account sessions and handles Google/SSO.
        Bundle extras = new Bundle();
        extras.putBinder("android.support.customtabs.extra.SESSION", null);
        intent.putExtras(extras);
        intent.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", color);
        intent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
        try { startActivity(intent); }
        catch (ActivityNotFoundException missing) {
            Toast.makeText(this, "HTTPS를 열 수 있는 브라우저를 설치하세요.", Toast.LENGTH_LONG).show();
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("Code Spend")
                .setMessage("Claude Code · Codex 비용 조회\n\n"
                        + "구독 계정: 공식 계정 화면에서 사용량·크레딧·결제 정보를 확인합니다. "
                        + "앱에 구독 청구액을 자동 수집하는 기능은 없습니다.\n\n"
                        + "API 비용: 관리자 API 키로 지정한 전용 공간의 청구 비용을 직접 조회합니다. "
                        + "다른 도구도 같은 공간을 쓰면 그 비용이 포함됩니다.\n\n"
                        + "키는 Android Keystore로 암호화해 이 기기에 저장합니다. "
                        + "공식 비용 API에만 전송하며 계정 비밀번호는 저장하지 않습니다.")
                .setPositiveButton("닫기", null).show();
    }

    private void cancelRequests() {
        generation++;
        for (Future<?> job : pending) job.cancel(true);
        pending.clear();
        loading.clear();
    }

    private LinearLayout card(Provider provider) {
        LinearLayout box = vertical();
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.setBackground(shape(WHITE, LINE, 18));
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1, -2);
        outer.setMargins(0, dp(14), 0, 0);
        box.setLayoutParams(outer);
        LinearLayout title = horizontal();
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text(provider.mark, 17, WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mark.setBackground(shape(provider.color, provider.color, 11));
        title.addView(mark, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView name = text(provider.title, 20, INK, true);
        name.setPadding(dp(12), 0, 0, 0);
        title.addView(name, weighted());
        TextView type = text("api".equals(mode) ? "API" : "계정", 11, MUTED, true);
        title.addView(type);
        box.addView(title);
        return box;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setFontFeatureSettings("tnum");
        return view;
    }

    private TextView paragraph(String value) {
        TextView view = text(value, 13, MUTED, false);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, dp(8), 0, dp(12));
        return view;
    }

    private EditText input(String hint, boolean password) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (password
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return input;
    }

    private Button action(String title, int foreground, int background) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(foreground);
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x22000000), shape(background, LINE, 12), null));
        return button;
    }

    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private LinearLayout.LayoutParams fullButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String usd(BigDecimal value) {
        int places = value.signum() != 0 && value.abs().compareTo(new BigDecimal("0.01")) < 0 ? 6 : 2;
        return "$" + value.setScale(places, RoundingMode.HALF_UP).toPlainString();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("mode", mode);
        state.putInt("days", days);
    }
    @Override protected void onDestroy() {
        cancelRequests();
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
