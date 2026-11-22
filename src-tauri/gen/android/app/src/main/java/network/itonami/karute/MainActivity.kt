package network.itonami.karute

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

// Platform geometry only: Android owns system bars, cutouts and the keyboard.
class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // PHI stays out of screenshots, screen recording, casting and the Recents thumbnail (KOTOBA-159).
    // Debug builds only skip it, so App QA can take evidence screenshots; release builds always set it.
    if (!BuildConfig.DEBUG) {
      window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }
    setRecentsScreenshotEnabled(false)
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    val content = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
      view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
      insets
    }
    ViewCompat.requestApplyInsets(content)
  }
}
