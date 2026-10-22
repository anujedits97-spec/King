package dev.kairo.myra

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.Surface
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

/**
 * Shows nothing. It opens for a moment so Android lets the camera run (it blocks the camera from background apps),
 * takes one photo, gives it back to DeviceServer and closes.
 */
class CameraCaptureActivity : Activity() {
    private var started = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
    }

    override fun onResume() {
        super.onResume()
        // Only one shot per launch. onResume can run again when the screen comes back.
        if (started) return
        started = true
        val future = waiting
        val front = intent.getBooleanExtra(EXTRA_FRONT, false)
        val rotation = intent.getIntExtra(EXTRA_ROTATION, Surface.ROTATION_0)
        thread(name = "myra-camera-shot", isDaemon = true) {
            val result = try {
                SilentCamera.capture(applicationContext, front, rotation)
            } catch (e: Exception) {
                SilentCamera.Result(null, e.message)
            }
            future?.complete(result)
            runOnUiThread {
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    companion object {
        const val EXTRA_FRONT = "front"
        const val EXTRA_ROTATION = "rotation"

        @Volatile private var waiting: CompletableFuture<SilentCamera.Result>? = null

        /**
         * Starts the invisible capture. The returned future completes with the photo, or with the reason it failed.
         * Callers must wait with a timeout: if Android never opens the activity, the future never completes.
         */
        fun start(ctx: Context, front: Boolean, rotation: Int): CompletableFuture<SilentCamera.Result> {
            val f = CompletableFuture<SilentCamera.Result>()
            waiting = f
            try {
                ctx.startActivity(
                    Intent(ctx, CameraCaptureActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        .putExtra(EXTRA_FRONT, front)
                        .putExtra(EXTRA_ROTATION, rotation)
                )
            } catch (e: Exception) {
                f.complete(SilentCamera.Result(null, "Android blocked opening the camera from the background (${e.message})."))
            }
            return f
        }
    }
}
