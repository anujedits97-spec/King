package dev.kairo.myra

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * One still photo from the phone's camera: no preview on screen, no shutter button, nothing saved to Photos.
 *
 * Android does not let apps use the camera from the background, so this only works while Myraa is on
 * screen. CameraCaptureActivity makes that happen for a moment, then closes.
 */
object SilentCamera {
    class Result(val jpeg: ByteArray?, val error: String?)

    /** Takes one photo. Call from a background thread, never the main thread. */
    @SuppressLint("MissingPermission") // The caller checks CAMERA permission first.
    fun capture(ctx: Context, front: Boolean, displayRotation: Int): Result {
        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val wantedFacing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wantedFacing
        } ?: return Result(null, "This phone has no ${if (front) "front" else "back"} camera.")

        val chars = manager.getCameraCharacteristics(id)
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val deviceDegrees = displayRotation * 90
        // Same rule Android documents for JPEG_ORIENTATION: the front camera is mirrored, so it turns the other way.
        val jpegRotation = if (front) (sensor + deviceDegrees) % 360 else (sensor - deviceDegrees + 360) % 360

        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)
            ?: return Result(null, "The camera didn't offer a picture format I can use.")
        // A size near 1280 on the long side: enough detail for the AI, small enough to send.
        val size = sizes.minByOrNull { abs(maxOf(it.width, it.height) - 1280) } ?: sizes[0]

        val thread = HandlerThread("myra-camera").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        // A tiny off-screen preview target. Without one, the camera skips light and focus settling and the photo comes out dark.
        val preview = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
        val previewSurface = Surface(preview)
        val file = File(ctx.cacheDir, "camera_look.jpg")
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        return try {
            val opened = CompletableFuture<CameraDevice>()
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    // If we already gave up waiting, close it straight away so the camera isn't left open.
                    if (!opened.complete(camera)) camera.close()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    opened.completeExceptionally(IllegalStateException("the camera was disconnected"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    opened.completeExceptionally(IllegalStateException("the camera failed, code $error"))
                }
            }, handler)
            val cam = opened.get(5, TimeUnit.SECONDS)
            device = cam

            val configured = CompletableFuture<CameraCaptureSession>()
            cam.createCaptureSession(listOf(reader.surface, previewSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { configured.complete(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    configured.completeExceptionally(IllegalStateException("the camera wouldn't start"))
                }
            }, handler)
            val s = configured.get(5, TimeUnit.SECONDS)
            session = s

            // Let auto-exposure and auto-focus settle on the preview first (up to about 3 seconds).
            val settled = CompletableFuture<Unit>()
            var frames = 0
            s.setRepeatingRequest(
                cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(sess: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        frames++
                        val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                            frames >= 40
                        ) settled.complete(Unit)
                    }
                }, handler)
            runCatching { settled.get(3, TimeUnit.SECONDS) } // If it never settles, shoot anyway.

            val photo = CompletableFuture<ByteArray>()
            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.use { img ->
                    val buf = img.planes[0].buffer
                    photo.complete(ByteArray(buf.remaining()).also { buf.get(it) })
                }
            }, handler)

            s.stopRepeating()
            s.capture(
                cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.JPEG_ORIENTATION, jpegRotation)
                    set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(sess: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        photo.completeExceptionally(IllegalStateException("the shot failed"))
                    }
                }, handler)

            val raw = photo.get(6, TimeUnit.SECONDS)
            // Camera2 writes the rotation into the EXIF tag, and Documents.imageJpeg reads it and turns the picture upright.
            file.writeBytes(raw)
            Result(Documents.imageJpeg(ctx, Uri.fromFile(file), 1280), null)
        } catch (e: Exception) {
            Result(null, "The camera didn't take a picture${e.message?.let { " ($it)" }.orEmpty()}.")
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader.close() }
            runCatching { previewSurface.release(); preview.release() }
            runCatching { file.delete() }
            thread.quitSafely()
        }
    }
}
