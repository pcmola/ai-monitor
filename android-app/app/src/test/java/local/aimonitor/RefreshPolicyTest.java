package local.aimonitor;

import static org.junit.Assert.*;

import org.junit.Test;

public class RefreshPolicyTest {
  @Test
  public void lateResponsesCannotChangeANewerAttemptsSchedule() {
    RefreshPolicy policy = new RefreshPolicy();
    assertFalse(policy.isCurrentAttempt(Provider.CODEX, 0));
    policy.started(Provider.CODEX, 0);
    assertTrue(policy.isCurrentAttempt(Provider.CODEX, 0));
    policy.started(Provider.CODEX, 10_000);
    assertFalse(policy.isCurrentAttempt(Provider.CODEX, 0));
    assertTrue(policy.isCurrentAttempt(Provider.CODEX, 10_000));
    assertFalse(policy.isCurrentAttempt(Provider.CLAUDE, 10_000));
  }

  @Test
  public void replacedCredentialsInvalidateOldAttempts() {
    RefreshPolicy policy = new RefreshPolicy();
    policy.started(Provider.CODEX, 123);
    policy.reset(Provider.CODEX);
    assertFalse(policy.isCurrentAttempt(Provider.CODEX, 123));
    assertTrue(policy.eligible(Provider.CODEX, 123, false));
  }

  @Test
  public void startsImmediatelyIncludingAtZeroAndChecksDoNotConsumeEligibility() {
    RefreshPolicy policy = new RefreshPolicy();
    for (Provider provider : Provider.values()) {
      assertEquals(0, policy.nextAutomaticAt(provider));
      assertTrue(policy.eligible(provider, 0, false));
      assertTrue(policy.eligible(provider, 0, true));
      assertTrue(policy.eligible(provider, 0, false));
    }
  }

  @Test
  public void automaticCadenceIsIndependentForEachProvider() {
    RefreshPolicy policy = new RefreshPolicy();
    policy.started(Provider.CODEX, 0);
    assertTrue(policy.eligible(Provider.CLAUDE, 0, false));
    policy.started(Provider.CLAUDE, 1_000);
    assertEquals(60_000, policy.nextAutomaticAt(Provider.CODEX));
    assertEquals(3_601_000, policy.nextAutomaticAt(Provider.CLAUDE));
    assertFalse(policy.eligible(Provider.CODEX, 59_999, false));
    assertTrue(policy.eligible(Provider.CODEX, 60_000, false));
    assertFalse(policy.eligible(Provider.CLAUDE, 3_600_999, false));
    assertTrue(policy.eligible(Provider.CLAUDE, 3_601_000, false));
  }

  @Test
  public void manualRequestsHaveTenSecondMinimumAfterEveryAttempt() {
    RefreshPolicy policy = new RefreshPolicy();
    for (Provider provider : Provider.values()) {
      policy.started(provider, 0);
      assertFalse(policy.eligible(provider, 0, true));
      assertFalse(policy.eligible(provider, 9_999, true));
      assertTrue(policy.eligible(provider, 10_000, true));
      policy.started(provider, 10_000);
      assertFalse(policy.eligible(provider, 19_999, true));
      assertTrue(policy.eligible(provider, 20_000, true));
    }
  }

  @Test
  public void rateLimitHonorsClampedRetryAfterForAutomaticAndManual() {
    for (long seconds : new long[] {Long.MIN_VALUE, -1, 0, 1, 60, 120, 86_400, Long.MAX_VALUE}) {
      RefreshPolicy policy = new RefreshPolicy();
      long delay = Math.max(60, Math.min(86_400, seconds)) * 1_000;
      policy.started(Provider.CODEX, 100);
      policy.failed(Provider.CODEX, 429, seconds, 200);
      assertEquals(200 + delay, policy.nextAutomaticAt(Provider.CODEX));
      for (boolean manual : new boolean[] {false, true}) {
        assertFalse(policy.eligible(Provider.CODEX, 199 + delay, manual));
        assertTrue(policy.eligible(Provider.CODEX, 200 + delay, manual));
      }
      assertTrue(policy.eligible(Provider.CLAUDE, 0, false));
    }
  }

  @Test
  public void networkAndServerFailuresDoubleBackoffUpToFifteenMinutes() {
    RefreshPolicy policy = new RefreshPolicy();
    long now = 0;
    long[] delays = {60_000, 120_000, 240_000, 480_000, 900_000, 900_000, 900_000};
    for (int i = 0; i < delays.length; i++) {
      assertTrue(policy.eligible(Provider.CLAUDE, now, false));
      policy.started(Provider.CLAUDE, now);
      policy.failed(Provider.CLAUDE, i % 2 == 0 ? 0 : 503, Long.MAX_VALUE, now);
      assertEquals(now + delays[i], policy.nextAutomaticAt(Provider.CLAUDE));
      assertFalse(policy.eligible(Provider.CLAUDE, now + delays[i] - 1, false));
      assertFalse(policy.eligible(Provider.CLAUDE, now + 9_999, true));
      assertTrue(policy.eligible(Provider.CLAUDE, now + 10_000, true));
      now += delays[i];
    }
  }

  @Test
  public void successfulAttemptRestartsTransientFailureBackoff() {
    RefreshPolicy policy = new RefreshPolicy();
    policy.started(Provider.CODEX, 0);
    policy.failed(Provider.CODEX, -1, 0, 0);
    policy.started(Provider.CODEX, 60_000);
    policy.succeeded(Provider.CODEX);
    policy.started(Provider.CODEX, 120_000);
    policy.failed(Provider.CODEX, 500, 0, 120_000);
    assertEquals(180_000, policy.nextAutomaticAt(Provider.CODEX));
  }

  @Test
  public void successPreservesAutomaticAndManualDeadlinesForBothProviders() {
    for (Provider provider : Provider.values()) {
      RefreshPolicy policy = new RefreshPolicy();
      policy.started(provider, 0);
      policy.failed(provider, 500, 0, 0);
      policy.started(provider, 60_000);
      long nextAutomatic = policy.nextAutomaticAt(provider);
      policy.succeeded(provider);
      assertEquals(nextAutomatic, policy.nextAutomaticAt(provider));
      assertFalse(policy.eligible(provider, 69_999, true));
      assertTrue(policy.eligible(provider, 70_000, true));
      assertFalse(policy.eligible(provider, nextAutomatic - 1, false));
      assertTrue(policy.eligible(provider, nextAutomatic, false));
      Provider other = provider == Provider.CODEX ? Provider.CLAUDE : Provider.CODEX;
      assertTrue(policy.eligible(other, 0, false));
    }
  }

  @Test
  public void successClearsAuthSuspensionWithoutResettingAttemptTiming() {
    RefreshPolicy policy = new RefreshPolicy();
    policy.started(Provider.CODEX, 0);
    policy.failed(Provider.CODEX, 401, 0, 0);
    policy.started(Provider.CODEX, 10_000);
    policy.succeeded(Provider.CODEX);
    assertEquals(70_000, policy.nextAutomaticAt(Provider.CODEX));
    assertFalse(policy.eligible(Provider.CODEX, 19_999, true));
    assertTrue(policy.eligible(Provider.CODEX, 20_000, true));
    assertFalse(policy.eligible(Provider.CODEX, 69_999, false));
    assertTrue(policy.eligible(Provider.CODEX, 70_000, false));
  }

  @Test
  public void successDoesNotChangeExistingRateLimitDeadline() {
    RefreshPolicy policy = new RefreshPolicy();
    policy.started(Provider.CODEX, 0);
    policy.failed(Provider.CODEX, 429, 120, 0);
    policy.succeeded(Provider.CODEX);
    assertEquals(120_000, policy.nextAutomaticAt(Provider.CODEX));
    assertFalse(policy.eligible(Provider.CODEX, 119_999, true));
    assertTrue(policy.eligible(Provider.CODEX, 120_000, false));
  }

  @Test
  public void authFailureStopsAutomaticUntilResetButAllowsManualAfterCooldown() {
    for (int status : new int[] {401, 403}) {
      RefreshPolicy policy = new RefreshPolicy();
      policy.started(Provider.CODEX, 0);
      policy.failed(Provider.CODEX, status, Long.MAX_VALUE, 0);
      assertEquals(Long.MAX_VALUE, policy.nextAutomaticAt(Provider.CODEX));
      assertFalse(policy.eligible(Provider.CODEX, 9_999, true));
      assertTrue(policy.eligible(Provider.CODEX, 10_000, true));
      assertFalse(policy.eligible(Provider.CODEX, Long.MAX_VALUE, false));
      policy.started(Provider.CODEX, 10_000);
      assertFalse(policy.eligible(Provider.CODEX, 100_000, false));
      assertTrue(policy.eligible(Provider.CLAUDE, 0, false));
      policy.reset(Provider.CODEX);
      assertEquals(0, policy.nextAutomaticAt(Provider.CODEX));
      assertTrue(policy.eligible(Provider.CODEX, 0, false));
      assertTrue(policy.eligible(Provider.CODEX, 0, true));
    }
  }

  @Test
  public void resetClearsRateLimitCooldownAndFailureSequenceForOnlyOneProvider() {
    RefreshPolicy policy = new RefreshPolicy();
    policy.started(Provider.CLAUDE, 0);
    policy.started(Provider.CODEX, 0);
    policy.failed(Provider.CODEX, 0, 0, 0);
    policy.failed(Provider.CODEX, 429, 86_400, 0);
    policy.reset(Provider.CODEX);
    assertTrue(policy.eligible(Provider.CODEX, 0, true));
    assertTrue(policy.eligible(Provider.CODEX, 0, false));
    assertFalse(policy.eligible(Provider.CLAUDE, 0, false));
    policy.started(Provider.CODEX, 0);
    policy.failed(Provider.CODEX, 500, 0, 0);
    assertEquals(60_000, policy.nextAutomaticAt(Provider.CODEX));
  }

  @Test
  public void otherErrorsAndFailuresWithoutStartedCannotHotLoop() {
    for (int status : new int[] {0, -1, 302, 400, 404, 500}) {
      RefreshPolicy policy = new RefreshPolicy();
      policy.failed(Provider.CODEX, status, 0, 1_000);
      assertEquals(61_000, policy.nextAutomaticAt(Provider.CODEX));
      assertFalse(policy.eligible(Provider.CODEX, 1_000, false));
      assertFalse(policy.eligible(Provider.CODEX, 10_999, true));
      assertTrue(policy.eligible(Provider.CODEX, 11_000, true));
    }
  }

  @Test
  public void saturationAndEarlierClockValuesNeverEnableImmediateRetry() {
    for (Provider provider : Provider.values()) {
      RefreshPolicy policy = new RefreshPolicy();
      long now = Long.MAX_VALUE - 5_000;
      policy.started(provider, now);
      assertEquals(Long.MAX_VALUE, policy.nextAutomaticAt(provider));
      assertFalse(policy.eligible(provider, now - 1, true));
      assertFalse(policy.eligible(provider, Long.MAX_VALUE, true));
      assertFalse(policy.eligible(provider, Long.MAX_VALUE, false));
      policy.failed(provider, 429, Long.MAX_VALUE, now);
      assertEquals(Long.MAX_VALUE, policy.nextAutomaticAt(provider));
      assertFalse(policy.eligible(provider, Long.MAX_VALUE, false));
      assertFalse(policy.eligible(provider, Long.MAX_VALUE, true));
      policy.reset(provider);
      assertTrue(policy.eligible(provider, Long.MAX_VALUE, false));
      policy.failed(provider, 500, 0, Long.MAX_VALUE);
      assertFalse(policy.eligible(provider, Long.MAX_VALUE, false));
    }
  }
}
