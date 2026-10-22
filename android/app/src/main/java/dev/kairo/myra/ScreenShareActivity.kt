package dev.kairo.myra

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast

/**
 * Shows Android's own "Start recording or casting?" dialog. Android requires this for every screen share.
 * With "Screen share without tapping" switched on (and Myraa's Accessibility service on), MyraAccess taps
 * Start on that dialog, but only in the short window right after Myraa opened it. Otherwise the user taps it.
 */
@Suppress("DEPRECATION") // startActivityForResult is still the simplest way to get the consent result here.
class ScreenShareActivity : Activity() {
    private var interval = LiveScreenService.DEFAULT_INTERVAL_SEC
    private var asked = false

    override fun onResume() {
        super.onResume()
        if (asked) return
        asked = true
        interval = intent.getIntExtra(EXTRA_INTERVAL, LiveScreenService.DEFAULT_INTERVAL_SEC)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: ask for the whole default display, so the dialog has no "one app / entire screen" chooser to pick from.
        val ask = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent()
        startActivityForResult(ask, REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                LiveScreenService.start(this, resultCode, data, interval)
            } else {
                Toast.makeText(this, "Live screen was not started.", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_INTERVAL = "interval"
        private const val REQUEST = 41

        /** Until this time (ms), MyraAccess may tap Start on the system dialog. 0 = never. */
        @Volatile var autoUntil = 0L

        fun launch(ctx: Context, intervalSec: Int) {
            autoUntil = if (Prefs(ctx).autoShare && MyraAccess.instance != null) System.currentTimeMillis() + 20_000 else 0L
            ctx.startActivity(
                Intent(ctx, ScreenShareActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(EXTRA_INTERVAL, intervalSec),
            )
        }
    }
}
