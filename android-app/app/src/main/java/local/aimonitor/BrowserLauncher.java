package local.aimonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Browser;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/** Public URLs only: no authentication material is passed to another application. */
public final class BrowserLauncher {
  // Query locally; this address is never loaded and cannot match a service-specific App Link.
  private static Intent probe() {
    return new Intent(Intent.ACTION_VIEW, Uri.parse("https://codespend-browser-check.invalid/"))
        .addCategory(Intent.CATEGORY_BROWSABLE);
  }

  private BrowserLauncher() {}

  public static void open(Activity activity, Provider provider) {
    try {
      PackageManager manager = activity.getPackageManager();
      List<BrowserSelection.Candidate> discovered = new ArrayList<>();
      for (ResolveInfo resolved :
          manager.queryIntentActivities(
              probe(),
              PackageManager.MATCH_ALL
                  | PackageManager.MATCH_DEFAULT_ONLY
                  | PackageManager.GET_RESOLVED_FILTER)) {
        if (resolved.activityInfo == null || resolved.activityInfo.applicationInfo == null)
          continue;
        CharSequence label = resolved.loadLabel(manager);
        discovered.add(
            new BrowserSelection.Candidate(
                resolved.activityInfo.packageName,
                resolved.activityInfo.name,
                label == null ? null : label.toString(),
                handlesAllWebUrls(resolved.filter),
                resolved.activityInfo.enabled && resolved.activityInfo.applicationInfo.enabled,
                resolved.activityInfo.exported));
      }
      List<BrowserSelection.Candidate> browsers = BrowserSelection.eligible(discovered);
      ResolveInfo defaultHandler =
          manager.resolveActivity(probe(), PackageManager.MATCH_DEFAULT_ONLY);
      String defaultPackage =
          defaultHandler == null || defaultHandler.activityInfo == null
              ? null
              : defaultHandler.activityInfo.packageName;
      if (browsers.size() == 1) {
        launch(activity, provider, browsers.get(0));
      } else if (!browsers.isEmpty()) {
        List<BrowserSelection.Candidate> choices =
            BrowserSelection.choices(browsers, defaultPackage);
        String[] names = new String[choices.size()];
        for (int i = 0; i < names.length; i++) names[i] = BrowserSelection.label(choices.get(i));
        new AlertDialog.Builder(activity)
            .setTitle("사용량 페이지를 열 브라우저")
            .setItems(names, (dialog, which) -> launch(activity, provider, choices.get(which)))
            .setNeutralButton("주소 복사", (dialog, which) -> copyUrl(activity, provider))
            .setNegativeButton("취소", null)
            .show();
      } else {
        unavailable(activity, provider);
      }
    } catch (Exception ignored) {
      unavailable(activity, provider);
    }
  }

  private static boolean handlesAllWebUrls(IntentFilter filter) {
    return filter != null
        && filter.hasAction(Intent.ACTION_VIEW)
        && filter.hasCategory(Intent.CATEGORY_BROWSABLE)
        && filter.hasDataScheme("https")
        && filter.countDataAuthorities() == 0
        && filter.countDataPaths() == 0
        && filter.countDataSchemeSpecificParts() == 0
        && filter.countDataTypes() == 0;
  }

  private static void launch(
      Activity activity, Provider provider, BrowserSelection.Candidate browser) {
    try {
      // Constrain the package, not a generic probe's Activity: each browser chooses its URL entry.
      // A new tab avoids restoring an existing home tab. Never forward login credentials/headers.
      activity.startActivity(
          new Intent(Intent.ACTION_VIEW, Uri.parse(provider.accountUrl))
              .addCategory(Intent.CATEGORY_BROWSABLE)
              .setPackage(browser.packageName())
              .putExtra(Browser.EXTRA_CREATE_NEW_TAB, true));
    } catch (Exception ignored) {
      // Never retry with an implicit VIEW intent: that could reopen the service app's home page.
      unavailable(activity, provider);
    }
  }

  private static void unavailable(Activity activity, Provider provider) {
    new AlertDialog.Builder(activity)
        .setTitle("웹 브라우저를 열 수 없습니다")
        .setMessage(
            "Chrome·삼성 인터넷 등 브라우저 설치·활성화 상태를 확인하세요.\n\n"
                + provider.accountUrl
                + "\n\n브라우저 로그인은 앱의 사용량 인증과 별개입니다.")
        .setPositiveButton("주소 복사", (dialog, which) -> copyUrl(activity, provider))
        .setNegativeButton("닫기", null)
        .show();
  }

  private static void copyUrl(Activity activity, Provider provider) {
    ClipboardManager clipboard =
        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard != null) {
      clipboard.setPrimaryClip(ClipData.newPlainText("공식 사용량 페이지", provider.accountUrl));
      Toast.makeText(activity, "공개 페이지 주소를 복사했습니다.", Toast.LENGTH_SHORT).show();
    }
  }
}
