package com.zandaulion.bitey

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import zxingcpp.BarcodeReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private lateinit var torchButton: Button
    private var camera: Camera? = null
    private var torchOn = false

    /** The overlay's words, in the app's language, as the page sent them. */
    private val labels by lazy {
        runCatching { JSONObject(intent.getStringExtra(EXTRA_LABELS).orEmpty()) }.getOrNull()
    }
    private fun label(key: String, fallback: String) =
        labels?.optString(key)?.takeIf { it.isNotBlank() } ?: fallback

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
                text = label("hint", "Hold the barcode about 15 cm away. Tap to focus.")
                setTextColor(Color.WHITE)
                setTextSize(16f)
                setPadding(32, 28, 32, 28)
                setBackgroundColor(0x99000000.toInt())
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                torchButton = Button(context).apply {
                    text = label("lightOn", "Light")
                    // Shown once the camera reports a flash unit.
                    visibility = View.GONE
                    setOnClickListener { toggleTorch() }
                }
                addView(torchButton)
                addView(Button(context).apply {
                    text = label("cancel", "Cancel")
                    setOnClickListener { finish() }
                })
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = 48 })
        }
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        torchButton.text = if (torchOn) label("lightOff", "Light off") else label("lightOn", "Light")
    }

    /**
     * Focus where the person taps. Continuous autofocus hunts on a glossy
     * packet held close, and a blurred code is the commonest reason nothing
     * reads; a tap says exactly where the barcode is.
     */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                .build(),
        )
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
        // Frames large enough for the bars to be read. CameraX's default for
        // analysis is about 640x480, and held upright that leaves a barcode a
        // couple of hundred pixels across: bars under two pixels wide, where
        // a little blur or glare and nothing decodes. 1080p gives each bar
        // room; a phone that cannot do it falls back to the nearest size.
        val analysisResolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1920, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(analysisResolution)
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
        val cam = runCatching {
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }.getOrElse {
            finish()
            return
        }
        camera = cam

        // A little zoom, so the code fills the frame from 15-20 cm, a
        // distance the lens can focus at. Without it people bring the packet
        // closer than the camera's nearest focus and the bars blur.
        val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        cam.cameraControl.setZoomRatio(minOf(START_ZOOM, maxZoom))

        if (cam.cameraInfo.hasFlashUnit()) torchButton.visibility = View.VISIBLE

        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focusAt(event.x, event.y)
                view.performClick()
            }
            true
        }
        // Start focused on the middle, where the hint asks for the code.
        previewView.post { focusAt(previewView.width / 2f, previewView.height / 2f) }
    }

    companion object {
        const val EXTRA_BARCODE = "com.zandaulion.bitey.extra.BARCODE"
        /** JSON of overlay strings from the page: hint, cancel, lightOn, lightOff. */
        const val EXTRA_LABELS = "com.zandaulion.bitey.extra.LABELS"
        private const val START_ZOOM = 2f
    }
}
