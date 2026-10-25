package local.aimonitor;

/** Same usage thresholds and RGB values as usage_monitor.py's web dashboard. */
public final class UsageColors {
  public static final int OK = 0xff2f9e6b;
  public static final int WARNING = 0xffd98a1c;
  public static final int CRITICAL = 0xffd64545;
  public static final int UNKNOWN = 0xff61758b;
  public static final int TRACK = 0xffe8edf3;

  private UsageColors() {}

  public static int bar(double usedPercent, boolean expired) {
    if (expired || !Double.isFinite(usedPercent)) return UNKNOWN;
    return usedPercent >= 90 ? CRITICAL : usedPercent >= 70 ? WARNING : OK;
  }
}
