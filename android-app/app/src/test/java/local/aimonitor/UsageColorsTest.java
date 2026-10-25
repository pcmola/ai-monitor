package local.aimonitor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UsageColorsTest {
  @Test
  public void matchesWebColorsAtExactBoundaries() {
    assertEquals(0xff2f9e6b, UsageColors.bar(0, false));
    assertEquals(0xff2f9e6b, UsageColors.bar(69.999, false));
    assertEquals(0xffd98a1c, UsageColors.bar(70, false));
    assertEquals(0xffd98a1c, UsageColors.bar(89.999, false));
    assertEquals(0xffd64545, UsageColors.bar(90, false));
    assertEquals(0xffd64545, UsageColors.bar(100, false));
    assertEquals(0xffe8edf3, UsageColors.TRACK);
  }

  @Test
  public void staleReadingsStayGrayInsteadOfImplyingCurrentAvailability() {
    for (double used : new double[] {0, 15, 51, 70, 90, 100})
      assertEquals(UsageColors.UNKNOWN, UsageColors.bar(used, true));
  }

  @Test
  public void invalidReadingsCannotLookHealthy() {
    for (double used :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
      assertEquals(UsageColors.UNKNOWN, UsageColors.bar(used, false));
  }
}
