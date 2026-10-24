package local.aimonitor;

import java.util.EnumMap;

/** Refresh timing driven only by caller-supplied monotonic milliseconds. */
public final class RefreshPolicy {
  private static final long MANUAL_INTERVAL = 10_000;
  private static final long INITIAL_BACKOFF = 60_000;
  private static final long MAX_BACKOFF = 15 * 60_000;
  private static final long MAX_RETRY_SECONDS = 24 * 60 * 60;

  private final EnumMap<Provider, State> states = new EnumMap<>(Provider.class);

  public RefreshPolicy() {
    for (Provider provider : Provider.values()) states.put(provider, new State());
  }

  public synchronized boolean eligible(Provider provider, long nowMillis, boolean manual) {
    State state = state(provider);
    if (state.attempted && !elapsed(nowMillis, state.lastAttempt, MANUAL_INTERVAL)) return false;
    if (!reached(nowMillis, state.retryAt)) return false;
    return manual || (!state.authBlocked && reached(nowMillis, state.automaticAt));
  }

  public synchronized void started(Provider provider, long nowMillis) {
    State state = state(provider);
    state.attempted = true;
    state.lastAttempt = nowMillis;
    state.automaticAt = add(nowMillis, provider == Provider.CODEX ? 60_000 : 3_600_000);
  }

  public synchronized boolean isCurrentAttempt(Provider provider, long startedAt) {
    State state = state(provider);
    return state.attempted && state.lastAttempt == startedAt;
  }

  public synchronized void failed(
      Provider provider, int httpStatus, long retryAfterSeconds, long nowMillis) {
    State state = state(provider);
    if (!state.attempted) {
      state.attempted = true;
      state.lastAttempt = nowMillis;
    }
    if (httpStatus == 401 || httpStatus == 403) {
      state.authBlocked = true;
      state.backoff = INITIAL_BACKOFF;
    } else if (httpStatus == 429) {
      long seconds = Math.max(60, Math.min(MAX_RETRY_SECONDS, retryAfterSeconds));
      state.retryAt = Math.max(state.retryAt, add(nowMillis, seconds * 1_000));
      state.automaticAt = state.retryAt;
      state.backoff = INITIAL_BACKOFF;
    } else {
      // Network errors use nonpositive status; server failures use HTTP 5xx.
      boolean transientFailure = httpStatus <= 0 || (httpStatus >= 500 && httpStatus <= 599);
      state.automaticAt = add(nowMillis, transientFailure ? state.backoff : INITIAL_BACKOFF);
      state.backoff = transientFailure ? Math.min(MAX_BACKOFF, state.backoff * 2) : INITIAL_BACKOFF;
    }
  }

  /** Record confirmed network success without changing either refresh deadline. */
  public synchronized void succeeded(Provider provider) {
    State state = state(provider);
    state.backoff = INITIAL_BACKOFF;
    state.authBlocked = false;
  }

  /** Call after replacing credentials to clear all previous scheduling restrictions. */
  public synchronized void reset(Provider provider) {
    state(provider);
    states.put(provider, new State());
  }

  /** Zero means initially eligible; Long.MAX_VALUE means automatic refresh is stopped. */
  public synchronized long nextAutomaticAt(Provider provider) {
    State state = state(provider);
    return state.authBlocked ? Long.MAX_VALUE : Math.max(state.automaticAt, state.retryAt);
  }

  private State state(Provider provider) {
    if (provider == null) throw new IllegalArgumentException("Provider is required.");
    return states.get(provider);
  }

  private static boolean elapsed(long now, long start, long duration) {
    return start <= Long.MAX_VALUE - duration && now >= start + duration;
  }

  private static boolean reached(long now, long deadline) {
    // Saturated deadlines must not create a hot loop at Long.MAX_VALUE.
    return deadline != Long.MAX_VALUE && now >= deadline;
  }

  private static long add(long now, long duration) {
    return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
  }

  private static final class State {
    boolean attempted;
    boolean authBlocked;
    long lastAttempt;
    long automaticAt;
    long retryAt;
    long backoff = INITIAL_BACKOFF;
  }
}
