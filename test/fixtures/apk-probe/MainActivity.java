package network.itonami.karute;
import android.app.Activity; import android.os.Bundle; import android.view.WindowManager;
// Mirrors karute's MainActivity.onCreate hardening, to get real aapt2/dexdump output for the APK check's tests.
public class MainActivity extends Activity {
  @Override protected void onCreate(Bundle savedInstanceState) {
    if (!BuildConfig.DEBUG) {
      getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
    }
    setRecentsScreenshotEnabled(false);
    super.onCreate(savedInstanceState);
  }
}
