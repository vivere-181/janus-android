/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.kde.kdeconnect_tp.R
import java.util.concurrent.Executors

/**
 * The camera, looking for Janus's pairing QR code; [onCode] gets the first one found (other QR codes are ignored).
 * Asks for the camera permission when shown.
 */
@Composable
fun QrScanner(onCode: (QrPairing.Code) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed) permission.launch(Manifest.permission.CAMERA) }

    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        if (allowed) {
            Camera(onCode)
            // Where to hold the code.
            Box(Modifier.fillMaxSize(0.62f).border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)))
        } else {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ms_photo_camera), null, Modifier.size(40.dp), tint = Color.White)
                Text(stringResource(R.string.qr_camera_needed), color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.qr_camera_allow)) }
            }
        }
    }
}

@Composable
private fun Camera(onCode: (QrPairing.Code) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var found by remember { mutableStateOf(false) }
    val reader = remember {
        MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
    }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val previewUse = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { image ->
                val code = if (found) null else decode(reader, image)
                image.close()
                if (code != null) {
                    found = true
                    ContextCompat.getMainExecutor(context).execute { onCode(code) }
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView({ preview }, Modifier.fillMaxSize())
}

/** The luminance (Y) plane is all a QR reader needs. */
private fun decode(reader: MultiFormatReader, image: ImageProxy): QrPairing.Code? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
    return try {
        QrPairing.parse(reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text)
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
