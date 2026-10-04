/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import ge.connect.ui.theme.AppTheme
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.share.SharePlugin
import org.kde.kdeconnect_tp.R
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera phone's screen for the remote camera: a normal preview here, small JPEG previews streamed to the other
 * phone (only when the last one has gone out, so a slow network drops frames instead of adding lag), and photos saved
 * to this phone's gallery and sent to the other phone.
 */
class CameraHostActivity : AppCompatActivity(), CameraHost.Screen {
    override lateinit var controllerId: String
    private var controller: Device? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var capture: ImageCapture? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val inFlight = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private var previewView: PreviewView? = null
    private val main = Handler(Looper.getMainLooper())
    private var countdown by mutableIntStateOf(0)
    private var flash by mutableStateOf(false)

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            controller?.let { CameraHost.status(it, "denied") }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        controllerId = intent.getStringExtra(EXTRA_CONTROLLER) ?: run { finish(); return }
        controller = KdeConnect.getInstance().getDevice(controllerId)?.takeIf { it.isReachable } ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        CameraHost.cancelRequest(this)
        CameraHost.screen?.takeIf { it !== this }?.stop()
        CameraHost.screen = this
        setContent { AppTheme { Screen() } }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    @androidx.compose.runtime.Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { context -> PreviewView(context).also { previewView = it; it.scaleType = PreviewView.ScaleType.FIT_CENTER } },
                modifier = Modifier.fillMaxSize(),
            )
            if (flash) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f)))
            if (countdown > 0) {
                Text("$countdown", color = Color.White, fontSize = 120.sp, modifier = Modifier.align(Alignment.Center))
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ms_photo_camera), null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.camera_host_shared, controller?.name.orEmpty()),
                        color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    FilledTonalButton(onClick = ::stop) { Text(stringResource(R.string.camera_stop)) }
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::switchLens, modifier = Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.4f), CircleShape)) {
                    Icon(painterResource(R.drawable.ms_cameraswitch), stringResource(R.string.camera_switch), tint = Color.White)
                }
                Box(
                    Modifier.size(76.dp).background(Color.White, CircleShape).padding(6.dp).background(Color.Black, CircleShape).padding(3.dp)
                        .background(Color.White, CircleShape)
                        .then(Modifier.let { m -> m }),
                ) {
                    IconButton(onClick = { shutter(0) }, modifier = Modifier.fillMaxSize()) {}
                }
                Box(Modifier.size(56.dp))
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also { preview -> previewView?.let { preview.surfaceProvider = it.surfaceProvider } }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    val now = System.currentTimeMillis()
                    val device = controller
                    if (device != null && now - lastFrameAt >= FRAME_INTERVAL_MS && inFlight.compareAndSet(false, true)) {
                        lastFrameAt = now
                        val jpeg = encode(image.toBitmap(), image.imageInfo.rotationDegrees)
                        device.sendPacket(
                            NetworkPacket(GeCameraPlugin.FRAME).apply { this["jpeg"] = Base64.encodeToString(jpeg, Base64.NO_WRAP) },
                            object : Device.SendPacketStatusCallback() {
                                override fun onSuccess() = inFlight.set(false)
                                override fun onFailure(e: Throwable) = inFlight.set(false)
                            },
                        )
                    }
                } catch (e: Exception) {
                    inFlight.set(false)
                    Log.w(TAG, "Frame failed: ${e.message}")
                } finally {
                    image.close()
                }
            }
            capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis, capture)
                controller?.let { CameraHost.status(it, "ready", lensName()) }
            } catch (e: Exception) {
                Log.w(TAG, "Camera failed: ${e.message}")
                controller?.let { CameraHost.status(it, "stopped") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun encode(bitmap: Bitmap, rotation: Int): ByteArray {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > FRAME_SIZE) bitmap.scale(bitmap.width * FRAME_SIZE / longest, bitmap.height * FRAME_SIZE / longest) else bitmap
        val upright = if (rotation == 0) scaled else Bitmap.createBitmap(
            scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(rotation.toFloat()) }, true,
        )
        return ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
    }

    private fun lensName() = if (lensFacing == CameraSelector.LENS_FACING_BACK) "back" else "front"

    override fun switchLens() = runOnUiThread {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        startCamera()
    }

    override fun shutter(delaySeconds: Int) = runOnUiThread {
        if (delaySeconds <= 0) takePhoto() else tick(delaySeconds)
    }

    private fun tick(left: Int) {
        countdown = left
        controller?.let { CameraHost.status(it, "countdown", lensName(), left) }
        if (left == 0) { takePhoto(); return }
        main.postDelayed({ tick(left - 1) }, 1_000)
    }

    private fun takePhoto() {
        countdown = 0
        val capture = capture ?: return
        val name = "Remote_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val options = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        flash = true
        main.postDelayed({ flash = false }, 150)
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val device = controller ?: return
                CameraHost.status(device, "captured", lensName())
                // The photo goes to the other phone as a normal transfer (it shows up in Recent there).
                output.savedUri?.let { uri -> Thread { device.getPlugin(SharePlugin::class.java)?.sendFiles(listOf(uri)) }.start() }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w(TAG, "Photo failed: ${exception.message}")
                Toast.makeText(this@CameraHostActivity, R.string.camera_photo_failed, Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun stop() = runOnUiThread { if (!isFinishing) finish() }

    override fun onStop() {
        super.onStop()
        // Leaving the screen stops sharing the camera (Android wouldn't let us keep it anyway).
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        if (CameraHost.screen === this) CameraHost.screen = null
        controller?.let { CameraHost.status(it, "stopped") }
        main.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CameraHost"
        private const val EXTRA_CONTROLLER = "controller"
        private const val FRAME_INTERVAL_MS = 150L
        private const val FRAME_SIZE = 640

        fun intent(context: Context, controllerId: String): Intent =
            Intent(context, CameraHostActivity::class.java).putExtra(EXTRA_CONTROLLER, controllerId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
