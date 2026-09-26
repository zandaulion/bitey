package com.zandaulion.bitey

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import zxingcpp.BarcodeReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native, offline barcode reader. zxing-cpp decodes each camera frame on the
 * device and nothing about the scan leaves it; this activity returns only the
 * decoded text to the page. A lookup with Open Food Facts happens later, and
 * only if the person allows it.
 */
class BarcodeScanActivity : ComponentActivity() {
    private val scanComplete = AtomicBoolean(false)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var previewView: PreviewView

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createView())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun createView(): FrameLayout {
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        return FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(TextView(context).apply {
                text = "Align the barcode inside the camera view"
                setTextColor(Color.WHITE)
                setTextSize(16f)
                setPadding(32, 28, 32, 28)
                setBackgroundColor(0x99000000.toInt())
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ))
            addView(Button(context).apply {
                text = "Cancel"
                setOnClickListener { finish() }
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = 48 })
        }
    }

    private fun startCamera() {
        val providerFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = runCatching { providerFuture.get() }.getOrElse {
                finish()
                return@addListener
            }
            bindCamera(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(cameraProvider: ProcessCameraProvider) {
        // The same symbologies the ML Kit scanner accepted: what food
        // packaging carries, plus Code 128 for shop-printed labels.
        val reader = BarcodeReader().apply {
            options.formats = setOf(
                BarcodeReader.Format.EAN_13,
                BarcodeReader.Format.EAN_8,
                BarcodeReader.Format.UPC_A,
                BarcodeReader.Format.UPC_E,
                BarcodeReader.Format.CODE_128,
            )
            // A packet held at an angle, or a code printed light on dark,
            // should still read. Each costs a little time per frame; with
            // STRATEGY_KEEP_ONLY_LATEST a slow frame is skipped, never queued.
            options.tryHarder = true
            options.tryRotate = true
            options.tryInvert = true
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(analysisExecutor) { imageProxy ->
                    // zxing-cpp reads synchronously, so the frame is closed
                    // here once it has been read, whatever the outcome; an
                    // unclosed frame stalls the camera.
                    try {
                        if (scanComplete.get()) return@setAnalyzer
                        val code = runCatching { reader.read(imageProxy) }.getOrNull()
                            ?.firstOrNull { it.error == null && !it.text.isNullOrBlank() }
                            ?.text
                        if (code != null && scanComplete.compareAndSet(false, true)) {
                            runOnUiThread {
                                setResult(RESULT_OK, Intent().putExtra(EXTRA_BARCODE, code))
                                finish()
                            }
                        }
                    } finally {
                        imageProxy.close()
                    }
                }
            }
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(this, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }

    companion object {
        const val EXTRA_BARCODE = "com.zandaulion.bitey.extra.BARCODE"
    }
}
