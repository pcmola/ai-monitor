package local.aimonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private static final int INK = 0xff142638, MUTED = 0xff61758b, BG = 0xfff2f5f8;
  private static final int LINE = 0xffdce4ec, WHITE = Color.WHITE, BAD = 0xffa7352a;
  private static final int IMPORT_CLAUDE = 41, IMPORT_CODEX = 42, IMPORT_CODEX_AUTH = 43;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Map<Provider, UsageReport> reports = new EnumMap<>(Provider.class);
  private static final Map<Provider, String> errors = new EnumMap<>(Provider.class);
  private final Map<Provider, Boolean> liveConnections = new EnumMap<>(Provider.class);
  // Keep cooldowns and access restrictions across Activity recreation, including rotation.
  private static final RefreshPolicy refreshPolicy = new RefreshPolicy();
  private CredentialStore store;
  private LinearLayout content;
  private ScrollView scrollView;
  private boolean busy;
  private int generation;
  private final Runnable automaticRefresh =
      new Runnable() {
        @Override
        public void run() {
          if (!busy) refreshLive(false);
          main.postDelayed(this, 1000L);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    store = new CredentialStore(this);
    restoreReports();
    buildUi();
    importDebugFiles();
    refreshLive(false);
  }

  private void restoreReports() {
    for (Provider provider : Provider.values()) {
      // Claude now uses the official browser session only. Do not load old tokens/reports.
      if (provider == Provider.CLAUDE) continue;
      try {
        JSONObject saved = store.load(provider);
        liveConnections.put(
            provider, saved != null && !saved.optString("accessToken", "").isEmpty());
        if (saved != null && saved.has("report"))
          reports.put(provider, UsageCodec.decode(saved.getJSONObject("report")));
      } catch (Exception ignored) {
        errors.put(provider, "저장된 자료를 읽지 못했습니다. 파일을 다시 가져오세요.");
      }
    }
  }

  private void buildUi() {
    int oldScroll = scrollView == null ? 0 : scrollView.getScrollY();
    LinearLayout root = vertical();
    root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          view.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              insets.getSystemWindowInsetBottom());
          return insets;
        });
    LinearLayout header = horizontal();
    header.setPadding(dp(14), dp(6), dp(12), dp(6));
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setBackgroundColor(INK);
    LinearLayout heading = vertical();
    heading.addView(text("구독 사용량", 22, WHITE, true));
    header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    Button refresh = button(busy ? "처리 중…" : "새로고침", WHITE, INK);
    refresh.setEnabled(!busy);
    refresh.setOnClickListener(v -> refreshLive(true));
    header.addView(refresh);
    Button help = button("?", WHITE, INK);
    help.setContentDescription("연결 안내");
    help.setOnClickListener(v -> showHelp());
    header.addView(help);
    root.addView(header);
    ScrollView scroll = new ScrollView(this);
    scrollView = scroll;
    scroll.setFillViewport(true);
    content = vertical();
    content.setPadding(dp(12), dp(2), dp(12), dp(12));
    for (Provider provider : Provider.values()) renderProvider(provider);
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    scroll.post(() -> scroll.scrollTo(0, oldScroll));
  }

  private void renderProvider(Provider provider) {
    UsageReport report = reports.get(provider);
    LinearLayout card = vertical();
    card.setPadding(dp(14), dp(10), dp(14), dp(10));
    card.setBackground(shape(WHITE, LINE, 18));
    LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, -2);
    margin.setMargins(0, dp(8), 0, 0);
    card.setLayoutParams(margin);
    LinearLayout title = horizontal();
    title.setGravity(Gravity.CENTER_VERTICAL);
    TextView mark = text(provider.mark, 18, WHITE, true);
    mark.setGravity(Gravity.CENTER);
    mark.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
    mark.setBackground(shape(provider.color, provider.color, 10));
    title.addView(mark, new LinearLayout.LayoutParams(dp(32), dp(32)));
    TextView name = text(provider.title, 20, INK, true);
    name.setPadding(dp(12), 0, dp(6), 0);
    title.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    title.addView(
        text(
            provider == Provider.CLAUDE
                ? "공식 웹"
                : report == null || report.plan == null ? "구독" : report.plan,
            12,
            MUTED,
            true));
    Button more = button("⋮", MUTED, WHITE);
    more.setContentDescription(provider.title + " 추가 메뉴");
    more.setEnabled(!busy);
    more.setOnClickListener(v -> providerMenu(provider, more));
    title.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
    card.addView(title);
    if (provider == Provider.CLAUDE) {
      card.addView(compactNote("휴대폰 브라우저에서 로그인하고 사용량을 확인하세요."));
      Button login = button("Claude 로그인 · 사용량 보기 ↗", WHITE, provider.color);
      login.setContentDescription(provider.title + " 공식 사용량 웹페이지를 브라우저로 열기");
      login.setOnClickListener(v -> BrowserLauncher.open(this, provider));
      card.addView(login, full());
      card.addView(compactNote("로그인은 브라우저에 유지됩니다. 앱 카드에는 수치가 연동되지 않습니다."));
      content.addView(card);
      return;
    }
    String error = errors.get(provider);
    if (error != null) {
      TextView warning = note(error + (report == null ? "" : "\n아래는 마지막으로 확인한 값입니다."));
      warning.setTextColor(BAD);
      card.addView(warning);
    }
    if (report == null) {
      card.addView(text("아직 가져온 사용량이 없습니다", 17, INK, true));
      card.addView(
          note(
              provider == Provider.CLAUDE
                  ? ".credentials.json 또는 claudeAiOauth.accessToken을 가져오세요. 워크스페이스 ID는 필요 없습니다."
                  : "PC의 .codex/auth.json을 한 번 가져오세요. 이후에는 PC 서버나 기록 재추출 없이 휴대폰이 직접 조회합니다."));
    } else {
      for (UsageReport.Window window : report.windows) renderWindow(card, window, provider);
      if (report.windows.isEmpty()) card.addView(note("이 기록에는 사용 한도 정보가 없습니다. 토큰 합계만 표시합니다."));
      card.addView(
          compactNote(
              "기준 "
                  + time(report.asOf)
                  + (hasLiveAuth(provider)
                      ? provider == Provider.CLAUDE ? " · 60분 갱신" : " · 1분 갱신"
                      : " · 가져온 기록")));
      if (provider == Provider.CODEX) {
        if (!hasLiveAuth(Provider.CODEX))
          card.addView(note("아래는 예전에 가져온 기록입니다. Codex 인증을 연결해야 현재 한도를 자동 갱신할 수 있습니다."));
        if (!report.usage.isEmpty()) {
          card.addView(text("가져온 기록의 토큰 사용량", 14, INK, true));
          card.addView(note("아래 기간은 가져올 때 계산한 값입니다. 전체 계정 합계가 아닐 수 있습니다."));
          for (UsageReport.Usage row : report.usage) {
            card.addView(
                text(
                    periodLabel(row.label()) + "   " + number(row.requests()) + "회",
                    13,
                    INK,
                    true));
            card.addView(
                note(
                    "출력 "
                        + compact(row.outputTokens())
                        + " · 총 토큰(캐시 포함) "
                        + compact(row.totalTokens())));
          }
        }
      }
    }
    Button connect = button("Codex 인증 가져오기", WHITE, provider.color);
    connect.setEnabled(!busy);
    connect.setOnClickListener(v -> codexDialog());
    LinearLayout actions = horizontal();
    actions.addView(connect, new LinearLayout.LayoutParams(0, -2, 1));
    Button official = button("사용량 웹페이지 ↗", provider.color, WHITE);
    official.setContentDescription(provider.title + " 공식 사용량 웹페이지를 브라우저로 열기");
    official.setOnClickListener(v -> BrowserLauncher.open(this, provider));
    LinearLayout.LayoutParams webSize = new LinearLayout.LayoutParams(0, -2, 1);
    webSize.setMargins(dp(8), 0, 0, 0);
    actions.addView(official, webSize);
    card.addView(actions, full());
    content.addView(card);
  }

  private void providerMenu(Provider provider, View anchor) {
    PopupMenu menu = new PopupMenu(this, anchor);
    menu.getMenu()
        .add("연결 안내")
        .setOnMenuItemClickListener(
            item -> {
              showHelp();
              return true;
            });
    if (provider == Provider.CODEX && !hasLiveAuth(Provider.CODEX)) {
      menu.getMenu()
          .add("기록 가져오기 (오프라인)")
          .setOnMenuItemClickListener(
              item -> {
                pick(IMPORT_CODEX, true);
                return true;
              });
    }
    if (store.configured(provider)) {
      menu.getMenu()
          .add("저장된 " + provider.title + " 자료 삭제")
          .setOnMenuItemClickListener(
              item -> {
                new AlertDialog.Builder(this)
                    .setTitle("이 기기의 자료만 삭제할까요?")
                    .setMessage("PC 원본 파일과 서비스 계정은 변경하지 않습니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton(
                        "삭제",
                        (d, which) -> {
                          generation++;
                          busy = false;
                          store.remove(provider);
                          liveConnections.put(provider, false);
                          refreshPolicy.reset(provider);
                          reports.remove(provider);
                          errors.remove(provider);
                          buildUi();
                        })
                    .show();
                return true;
              });
    }
    menu.show();
  }

  private void renderWindow(LinearLayout card, UsageReport.Window window, Provider provider) {
    boolean expired = window.resetsAt() != null && !window.resetsAt().isAfter(Instant.now());
    LinearLayout row = horizontal();
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.addView(
        text(periodLabel(window.label()), 14, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
    TextView remaining =
        text(
            expired ? "현재값 미확인" : percent(100 - window.usedPercent()) + "% 남음",
            expired ? 15 : 22,
            expired ? MUTED : INK,
            true);
    remaining.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    row.addView(remaining);
    card.addView(row);
    ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    bar.setMax(1000);
    bar.setProgress((int) Math.round(window.usedPercent() * 10));
    bar.setProgressTintList(ColorStateList.valueOf(UsageColors.bar(window.usedPercent(), expired)));
    bar.setProgressBackgroundTintList(ColorStateList.valueOf(UsageColors.TRACK));
    bar.setContentDescription(window.label() + " 기준 시각 사용률 " + percent(window.usedPercent()) + "%");
    LinearLayout.LayoutParams barSize = new LinearLayout.LayoutParams(-1, dp(6));
    barSize.setMargins(0, dp(5), 0, 0);
    card.addView(bar, barSize);
    card.addView(
        compactNote(
            expired
                ? "기록의 초기화 시각이 지났습니다. 현재 한도를 다시 확인하세요."
                : percent(window.usedPercent())
                    + "% 사용 · "
                    + (window.resetsAt() == null
                        ? "초기화 시각 미제공"
                        : "초기화 " + time(window.resetsAt()))));
  }

  private void pick(int request, boolean multiple) {
    if (request == IMPORT_CLAUDE) return;
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("*/*");
    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
    try {
      startActivityForResult(intent, request);
    } catch (Exception ignored) {
      Toast.makeText(this, "문서 선택기를 열 수 없습니다.", Toast.LENGTH_LONG).show();
    }
  }

  private void codexDialog() {
    LinearLayout form = vertical();
    form.setPadding(dp(22), dp(8), dp(22), 0);
    form.addView(
        note(
            "PC의 .codex/auth.json을 한 번 가져오면 휴대폰이 현재 한도를 직접 조회합니다. "
                + "PC 서버·JSONL 기록 재추출은 필요 없습니다. access_token만 직접 입력할 수도 있습니다. "
                + "refresh_token과 id_token은 저장하지 않습니다. 만료되면 인증을 다시 가져오세요."));
    EditText token = new EditText(this);
    token.setSingleLine(true);
    token.setTextSize(14);
    token.setHint("Codex access_token 또는 auth.json 내용");
    token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    token.setSaveEnabled(false);
    form.addView(token);
    Button file = button("auth.json 파일 선택", Provider.CODEX.color, WHITE);
    form.addView(file, full());
    TextView validation = text("", 12, BAD, false);
    form.addView(validation);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Codex 실시간 사용량 연결")
            .setView(scroll)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장 후 조회", null)
            .create();
    file.setOnClickListener(
        v -> {
          token.setText("");
          dialog.dismiss();
          pick(IMPORT_CODEX_AUTH, false);
        });
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        CodexUsageClient.Credential credential =
                            CodexUsageClient.parseCredential(
                                token.getText().toString().trim(), Instant.now());
                        generation++;
                        busy = false;
                        saveCodex(credential);
                        liveConnections.put(Provider.CODEX, true);
                        refreshPolicy.reset(Provider.CODEX);
                        reports.remove(Provider.CODEX);
                        errors.remove(Provider.CODEX);
                        token.setText("");
                        dialog.dismiss();
                        refreshLive(true);
                      } catch (Exception ignored) {
                        validation.setText("유효한 Codex 로그인 인증이 필요합니다. API 키·세션 기록은 여기에 넣지 마세요.");
                      }
                    }));
    dialog.show();
    if (dialog.getWindow() != null) {
      dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      dialog
          .getWindow()
          .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK
        || data == null
        || (request != IMPORT_CODEX && request != IMPORT_CODEX_AUTH)) return;
    List<Uri> uris = new ArrayList<>();
    if (data.getClipData() != null) {
      for (int i = 0; i < data.getClipData().getItemCount(); i++) {
        Uri uri = data.getClipData().getItemAt(i).getUri();
        if (!uris.contains(uri)) uris.add(uri);
      }
    } else if (data.getData() != null) uris.add(data.getData());
    if (uris.isEmpty() || uris.size() > 100) {
      Toast.makeText(this, "파일은 1~100개를 선택하세요.", Toast.LENGTH_LONG).show();
      return;
    }
    if (busy) return;
    busy = true;
    buildUi();
    int requestGeneration = ++generation;
    Provider importedProvider = request == IMPORT_CLAUDE ? Provider.CLAUDE : Provider.CODEX;
    long expectedRevision = store.revision(importedProvider);
    executor.submit(
        () -> {
          String failure = null;
          try {
            if (request == IMPORT_CLAUDE) {
              try (InputStream stream = getContentResolver().openInputStream(uris.get(0))) {
                saveClaude(
                    ClaudeUsageClient.parseCredential(readText(stream, 1024 * 1024), Instant.now()),
                    expectedRevision);
              }
            } else if (request == IMPORT_CODEX_AUTH) {
              try (InputStream stream = getContentResolver().openInputStream(uris.get(0))) {
                saveCodex(
                    CodexUsageClient.parseCredential(readText(stream, 1024 * 1024), Instant.now()),
                    expectedRevision);
              }
            } else {
              List<Reader> readers = new ArrayList<>();
              try {
                for (Uri uri : uris)
                  readers.add(
                      new InputStreamReader(
                          limited(getContentResolver().openInputStream(uri), 32L * 1024 * 1024),
                          StandardCharsets.UTF_8));
                UsageReport report = CodexUsageParser.parse(readers, Instant.now());
                saveCodexRecord(report, expectedRevision);
              } finally {
                for (Reader reader : readers)
                  try {
                    reader.close();
                  } catch (Exception ignored) {
                  }
              }
            }
          } catch (Exception ignored) {
            failure =
                request == IMPORT_CLAUDE
                    ? "인증 파일을 가져오지 못했습니다. 파일 형식과 만료 여부를 확인하세요."
                    : request == IMPORT_CODEX_AUTH
                        ? "Codex 인증을 가져오지 못했습니다. auth.json의 tokens.access_token을 확인하세요."
                        : "기록을 가져오지 못했습니다. token_count가 있는 JSONL 파일을 선택하세요 (파일당 32MB 이하).";
          }
          String message = failure;
          main.post(
              () -> {
                if (isDestroyed() || requestGeneration != generation) return;
                busy = false;
                Provider provider = request == IMPORT_CLAUDE ? Provider.CLAUDE : Provider.CODEX;
                if (message != null) errors.put(provider, message);
                else {
                  errors.remove(provider);
                  if (request != IMPORT_CODEX) {
                    reports.remove(provider);
                    refreshPolicy.reset(provider);
                  }
                  restoreReports();
                }
                buildUi();
                if (message == null && request != IMPORT_CODEX) refreshLive(true);
              });
        });
  }

  private void saveClaude(ClaudeUsageClient.Credential credential) throws Exception {
    saveClaude(credential, store.revision(Provider.CLAUDE));
  }

  private void saveClaude(ClaudeUsageClient.Credential credential, long expectedRevision)
      throws Exception {
    store.importCredential(
        Provider.CLAUDE,
        expectedRevision,
        new JSONObject()
            .put("accessToken", credential.accessToken())
            .put("expiresAt", credential.expiresAtMillis())
            .put("plan", credential.plan() == null ? JSONObject.NULL : credential.plan()));
  }

  private void saveCodex(CodexUsageClient.Credential credential) throws Exception {
    saveCodex(credential, store.revision(Provider.CODEX));
  }

  private void saveCodex(CodexUsageClient.Credential credential, long expectedRevision)
      throws Exception {
    JSONObject value =
        new JSONObject()
            .put("accessToken", credential.accessToken())
            .put(
                "accountId",
                credential.accountId() == null ? JSONObject.NULL : credential.accountId());
    store.importCredential(Provider.CODEX, expectedRevision, value);
  }

  private void saveCodexRecord(UsageReport report, long expectedRevision) throws Exception {
    store.importCodexRecord(expectedRevision, UsageCodec.encode(report));
  }

  private boolean hasLiveAuth(Provider provider) {
    return provider != Provider.CLAUDE && Boolean.TRUE.equals(liveConnections.get(provider));
  }

  private void refreshLive(boolean manual) {
    if (busy) return;
    long now = SystemClock.elapsedRealtime();
    List<Provider> selected = new ArrayList<>();
    for (Provider provider : Provider.values()) {
      if (hasLiveAuth(provider) && refreshPolicy.eligible(provider, now, manual))
        selected.add(provider);
    }
    if (selected.isEmpty()) {
      if (manual)
        Toast.makeText(this, "인증을 먼저 가져오거나 조회 간격·재시도 대기가 끝난 뒤 다시 누르세요.", Toast.LENGTH_LONG).show();
      return;
    }
    for (Provider provider : selected) refreshPolicy.started(provider, now);
    Map<Provider, Long> revisions = new EnumMap<>(Provider.class);
    for (Provider provider : selected) revisions.put(provider, store.revision(provider));
    busy = true;
    buildUi();
    int requestGeneration = ++generation;
    executor.submit(
        () -> {
          Map<Provider, UsageReport> completed = new EnumMap<>(Provider.class);
          Map<Provider, String> failures = new EnumMap<>(Provider.class);
          Map<Provider, Integer> statuses = new EnumMap<>(Provider.class);
          Map<Provider, Long> retries = new EnumMap<>(Provider.class);
          for (Provider provider : selected) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
              JSONObject saved = store.load(provider);
              if (saved == null) continue;
              String token = saved.getString("accessToken");
              String account = null;
              UsageReport report;
              if (provider == Provider.CLAUDE) {
                ClaudeUsageClient.Credential credential =
                    new ClaudeUsageClient.Credential(
                        token,
                        saved.optLong("expiresAt", 0),
                        saved.isNull("plan") ? null : saved.optString("plan", null));
                report = new ClaudeUsageClient().fetch(credential, Instant.now());
              } else {
                account = saved.isNull("accountId") ? null : saved.optString("accountId", null);
                report =
                    new CodexUsageClient()
                        .fetch(new CodexUsageClient.Credential(token, account), Instant.now());
              }
              if (!Thread.currentThread().isInterrupted()
                  && store.saveUsageReport(provider, token, account, UsageCodec.encode(report)))
                completed.put(provider, report);
            } catch (CodexUsageClient.HttpStatusException error) {
              statuses.put(provider, error.statusCode());
              retries.put(provider, error.retryAfterSeconds());
              failures.put(provider, httpFailure(provider, error.statusCode()));
            } catch (ClaudeUsageClient.HttpStatusException error) {
              statuses.put(provider, error.statusCode());
              retries.put(provider, error.retryAfterSeconds());
              failures.put(provider, httpFailure(provider, error.statusCode()));
            } catch (Exception ignored) {
              statuses.put(provider, 0);
              failures.put(
                  provider, provider.title + " 조회 실패: 연결 상태·토큰 만료 여부를 확인하고 필요하면 인증을 다시 가져오세요.");
            }
          }
          main.post(
              () -> {
                long finishedAt = SystemClock.elapsedRealtime();
                for (Provider provider : selected) {
                  // A rotation must not discard server Retry-After or auth suspension.
                  // A late response must not change a replacement credential's schedule.
                  if (store.revision(provider) != revisions.get(provider)
                      || !refreshPolicy.isCurrentAttempt(provider, now)) continue;
                  if (completed.containsKey(provider)) {
                    errors.remove(provider);
                    refreshPolicy.succeeded(provider);
                  }
                  if (failures.containsKey(provider)) {
                    errors.put(provider, failures.get(provider));
                    refreshPolicy.failed(
                        provider,
                        statuses.getOrDefault(provider, 0),
                        retries.getOrDefault(provider, 0L),
                        finishedAt);
                  }
                }
                if (isDestroyed() || requestGeneration != generation) return;
                busy = false;
                for (Provider provider : selected)
                  if (completed.containsKey(provider)
                      && store.revision(provider) == revisions.get(provider))
                    reports.put(provider, completed.get(provider));
                buildUi();
              });
        });
  }

  private String httpFailure(Provider provider, int status) {
    if (status == 429) return provider.title + " 조회 제한 (HTTP 429). 서버가 안내한 시간만큼 기다린 뒤 자동 재시도합니다.";
    if (status == 401 || status == 403)
      return provider.title
          + " 인증 또는 접근 제한 (HTTP "
          + status
          + "). 자동 재시도를 중단했습니다. 갱신된 인증 파일을 다시 가져오거나 공식 화면을 확인하세요.";
    return provider.title + " 조회 실패 (HTTP " + status + "). 잠시 뒤 자동 재시도합니다. 이전 값을 현재값으로 표시하지 않습니다.";
  }

  private void importDebugFiles() {
    if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
    boolean found = false;
    for (Provider provider : Provider.values())
      if (provider != Provider.CLAUDE && debugFile(provider).isFile()) found = true;
    if (!found) return;
    busy = true;
    buildUi();
    int requestGeneration = ++generation;
    long claudeRevision = store.revision(Provider.CLAUDE);
    long codexRevision = store.revision(Provider.CODEX);
    executor.submit(
        () -> {
          Map<Provider, String> importErrors = new EnumMap<>(Provider.class);
          boolean changedClaude = false;
          boolean changedCodex = false;
          for (Provider provider : Provider.values()) {
            if (provider == Provider.CLAUDE) continue;
            File file = debugFile(provider);
            if (!file.isFile()) continue;
            try {
              if (provider == Provider.CLAUDE) {
                try (InputStream stream = new FileInputStream(file)) {
                  saveClaude(
                      ClaudeUsageClient.parseCredential(
                          readText(stream, 1024 * 1024), Instant.now()),
                      claudeRevision);
                }
              } else if (file.getName().equals("pending-codex-auth.json")) {
                try (InputStream stream = new FileInputStream(file)) {
                  saveCodex(
                      CodexUsageClient.parseCredential(
                          readText(stream, 1024 * 1024), Instant.now()),
                      codexRevision);
                }
                changedCodex = true;
              } else {
                try (Reader reader =
                    new InputStreamReader(
                        limited(new FileInputStream(file), 32L * 1024 * 1024),
                        StandardCharsets.UTF_8)) {
                  UsageReport report =
                      CodexUsageParser.parse(
                          java.util.Collections.singletonList(reader), Instant.now());
                  saveCodexRecord(report, codexRevision);
                }
              }
              if (provider == Provider.CLAUDE) changedClaude = true;
            } catch (Exception ignored) {
              importErrors.put(provider, "PC 자료를 가져오지 못했습니다. 형식과 만료 여부를 확인하세요.");
            } finally {
              // Retain a non-secret Codex import on rotation/cancellation so the
              // replacement Activity can retry it. Never retain a token staging file.
              if (!(file.getName().equals("pending-codex.jsonl")
                      && Thread.currentThread().isInterrupted())
                  && !file.delete())
                importErrors.put(provider, "임시 가져오기 파일을 삭제하지 못했습니다. 앱 자료를 삭제하고 다시 연결하세요.");
            }
          }
          boolean replaced = changedClaude;
          boolean replacedCodex = changedCodex;
          main.post(
              () -> {
                if (isDestroyed() || requestGeneration != generation) return;
                busy = false;
                if (replaced) {
                  reports.remove(Provider.CLAUDE);
                  refreshPolicy.reset(Provider.CLAUDE);
                }
                if (replacedCodex) {
                  reports.remove(Provider.CODEX);
                  refreshPolicy.reset(Provider.CODEX);
                }
                errors.putAll(importErrors);
                restoreReports();
                buildUi();
                refreshLive(false);
              });
        });
  }

  private File debugFile(Provider provider) {
    if (provider == Provider.CLAUDE) return new File(getFilesDir(), "pending-claude.json");
    File auth = new File(getFilesDir(), "pending-codex-auth.json");
    return auth.isFile() ? auth : new File(getFilesDir(), "pending-codex.jsonl");
  }

  private static String readText(InputStream stream, int max) throws Exception {
    try (InputStream bounded = limited(stream, max);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = bounded.read(buffer)) != -1) bytes.write(buffer, 0, count);
      return bytes.toString(StandardCharsets.UTF_8.name());
    }
  }

  private static InputStream limited(InputStream stream, long max) throws java.io.IOException {
    if (stream == null) throw new java.io.IOException("파일을 열 수 없습니다.");
    return new java.io.FilterInputStream(stream) {
      private long remaining = max;

      @Override
      public int read() throws java.io.IOException {
        int value = super.read();
        if (value != -1 && --remaining < 0) throw new java.io.IOException("파일 크기 초과");
        return value;
      }

      @Override
      public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
        int count = super.read(bytes, offset, (int) Math.min(length, remaining + 1));
        if (count > 0 && (remaining -= count) < 0) throw new java.io.IOException("파일 크기 초과");
        return count;
      }
    };
  }

  private void showHelp() {
    new AlertDialog.Builder(this)
        .setTitle("사용량 연결 안내")
        .setMessage(
            "Claude: 'Claude 로그인 · 사용량 보기'를 누르고 공식 웹페이지에서 로그인하세요. 같은 브라우저를 사용하면 로그인 세션이 유지됩니다."
                + " 세션 만료·로그아웃·브라우저 데이터 삭제 시에는 다시 로그인해야 합니다. PC 인증 파일은 필요 없습니다."
                + " 앱은 브라우저의 비밀번호·쿠키·토큰을 읽거나 수치를 자동으로 가져오지 않습니다."
                + " 이전에 가져온 Claude 자료는 추가 메뉴에서 직접 삭제할 수 있습니다.\n\n"
                + "Codex: .codex/auth.json의 tokens.access_token과 account_id를 한 번 가져오면 휴대폰이 직접 현재"
                + " 한도를 조회합니다. 앱 화면이 열려 있을 때 1분마다, 다시 화면을 열면 갱신 시점이 지났을 때 조회합니다. PC 서버나 세션 기록 재추출은"
                + " 필요 없습니다. Codex 내부 사용량 주소이므로 변경·접근 제한 가능성이 있습니다. 인증 만료 시 다시 가져오세요."
                + " refresh_token은 저장하거나 사용하지 않습니다.\n\n"
                + "필요한 요약만 기기에 암호화해 저장합니다. 대화 기록과 refreshToken은 저장하지 않습니다. 인증 파일을 공유 폴더로 옮겼다면 가져온 뒤"
                + " 그 복사본을 삭제하세요. 이 화면은 구독 한도이며 실제 청구 비용이나 금액으로 환산하지 않습니다.")
        .setPositiveButton("닫기", null)
        .show();
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

  private TextView note(String value) {
    TextView view = text(value, 12, MUTED, false);
    view.setLineSpacing(dp(3), 1f);
    view.setPadding(0, dp(8), 0, dp(12));
    return view;
  }

  private TextView compactNote(String value) {
    TextView view = text(value, 11, MUTED, false);
    view.setPadding(0, dp(3), 0, dp(6));
    return view;
  }

  private Button button(String title, int foreground, int background) {
    Button button = new Button(this);
    button.setText(title);
    button.setAllCaps(false);
    button.setTextSize(13);
    button.setTextColor(foreground);
    button.setMinimumHeight(dp(48));
    button.setPadding(dp(12), dp(9), dp(12), dp(9));
    button.setBackground(
        new android.graphics.drawable.RippleDrawable(
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

  private LinearLayout.LayoutParams full() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, dp(8), 0, 0);
    return params;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private String time(Instant instant) {
    return DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.KOREAN)
        .withZone(ZoneId.systemDefault())
        .format(instant);
  }

  private String number(long value) {
    return String.format(Locale.US, "%,d", value);
  }

  private String periodLabel(String value) {
    return "5h".equals(value) ? "최근 5시간" : "7d".equals(value) ? "최근 7일" : value;
  }

  private String percent(double value) {
    return value == Math.rint(value)
        ? String.format(Locale.US, "%.0f", value)
        : String.format(Locale.US, "%.1f", value);
  }

  private String compact(long value) {
    return value >= 1_000_000
        ? String.format(Locale.US, "%.2fM", value / 1_000_000d)
        : value >= 1000 ? String.format(Locale.US, "%.1fK", value / 1000d) : Long.toString(value);
  }

  @Override
  protected void onResume() {
    super.onResume();
    main.removeCallbacks(automaticRefresh);
    if (!busy) refreshLive(false);
    main.postDelayed(automaticRefresh, 1000L);
  }

  @Override
  protected void onPause() {
    main.removeCallbacks(automaticRefresh);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    generation++;
    main.removeCallbacksAndMessages(null);
    executor.shutdownNow();
    super.onDestroy();
  }
}
