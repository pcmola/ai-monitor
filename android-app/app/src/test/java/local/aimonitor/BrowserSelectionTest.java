package local.aimonitor;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class BrowserSelectionTest {
  private BrowserSelection.Candidate browser(String name) {
    return new BrowserSelection.Candidate(name, name + ".Main", name, true, true, true);
  }

  @Test
  public void rejectsServiceSpecificAppLinksEvenWhenDefault() {
    var chat =
        new BrowserSelection.Candidate("com.openai.chatgpt", "Main", "ChatGPT", false, true, true);
    var claude =
        new BrowserSelection.Candidate("com.anthropic.claude", "Main", "Claude", false, true, true);
    var chrome = browser("com.android.chrome");
    var valid = BrowserSelection.eligible(List.of(chat, claude, chrome));
    assertEquals(List.of(chrome), valid);
    assertEquals(chrome, BrowserSelection.preferred(valid, chat.packageName()));
  }

  @Test
  public void acceptsAnyGeneralBrowserNotJustChrome() {
    var samsung = browser("com.sec.android.app.sbrowser");
    var firefox = browser("org.mozilla.firefox");
    assertEquals(2, BrowserSelection.eligible(List.of(samsung, firefox)).size());
  }

  @Test
  public void rejectsDisabledAndNonExportedActivities() {
    assertTrue(
        BrowserSelection.eligible(
                List.of(
                    new BrowserSelection.Candidate("disabled", "Main", null, true, false, true),
                    new BrowserSelection.Candidate("private", "Main", null, true, true, false)))
            .isEmpty());
  }

  @Test
  public void rejectsMissingPackageOrActivity() {
    assertTrue(
        BrowserSelection.eligible(
                Arrays.asList(
                    null,
                    new BrowserSelection.Candidate(null, "Main", null, true, true, true),
                    new BrowserSelection.Candidate(" ", "Main", null, true, true, true),
                    new BrowserSelection.Candidate("valid", null, null, true, true, true),
                    new BrowserSelection.Candidate("valid", "", null, true, true, true)))
            .isEmpty());
  }

  @Test
  public void deduplicatesBrowserActivitiesByPackage() {
    var first = browser("browser");
    var second = new BrowserSelection.Candidate("browser", "Other", "Other", true, true, true);
    assertEquals(List.of(first), BrowserSelection.eligible(List.of(first, second)));
  }

  @Test
  public void prefersDefaultOnlyWhenItIsAGeneralBrowser() {
    var a = browser("alpha");
    var b = browser("beta");
    var valid = BrowserSelection.eligible(List.of(a, b));
    assertEquals(b, BrowserSelection.preferred(valid, "beta"));
    assertNull(BrowserSelection.preferred(valid, "com.openai.chatgpt"));
    assertNull(BrowserSelection.preferred(valid, null));
  }

  @Test
  public void noBrowserReturnsNothingInsteadOfServiceAppFallback() {
    assertNull(BrowserSelection.preferred(List.of(), "com.openai.chatgpt"));
  }

  @Test
  public void labelsFallBackToPackageNameAndSortStably() {
    var b = new BrowserSelection.Candidate("z", "Main", "Alpha", true, true, true);
    var a = new BrowserSelection.Candidate("a", "Main", null, true, true, true);
    assertEquals("a", BrowserSelection.label(a));
    assertEquals(List.of(a, b), BrowserSelection.eligible(List.of(b, a)));
  }

  @Test
  public void usageLinksContainNoCredentialsAndCodexUsesWebSettings() {
    assertEquals("https://chatgpt.com/codex/settings/usage", Provider.CODEX.accountUrl);
    assertEquals("https://claude.ai/settings/usage", Provider.CLAUDE.accountUrl);
    for (Provider provider : Provider.values()) {
      assertFalse(provider.accountUrl.contains("?"));
      assertFalse(provider.accountUrl.contains("#"));
    }
  }

  @Test
  public void chromeIsOfferedFirstEvenWhenSamsungIsDefault() {
    var samsung = browser("com.sec.android.app.sbrowser");
    var chrome = browser("com.android.chrome");
    var firefox = browser("org.mozilla.firefox");
    var valid = BrowserSelection.eligible(List.of(samsung, firefox, chrome));
    assertEquals(
        List.of(chrome, samsung, firefox), BrowserSelection.choices(valid, samsung.packageName()));
    assertEquals(3, valid.size());
  }

  @Test
  public void withoutChromeDefaultRemainsAnExplicitAvailableChoice() {
    var samsung = browser("com.sec.android.app.sbrowser");
    var firefox = browser("org.mozilla.firefox");
    assertEquals(
        List.of(samsung, firefox),
        BrowserSelection.choices(
            BrowserSelection.eligible(List.of(firefox, samsung)), samsung.packageName()));
    assertTrue(BrowserSelection.choices(List.of(), null).isEmpty());
  }
}
