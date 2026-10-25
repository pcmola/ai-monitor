package local.aimonitor;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Separate test APK: reproduces a service app receiving verified web links. */
public final class LinkTrapActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    TextView text = new TextView(this);
    text.setText("Link trap reached: browser routing failed");
    setContentView(text);
  }
}
