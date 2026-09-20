package com.zandaulion.bitey

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.ValueCallback
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hosts the locally packaged Plate interface. The WebView is a presentation
 * layer only: native code will own persistence, camera/barcode access and the
 * narrowly-scoped network calls rather than relying on a web server.
 */
class MainActivity : ComponentActivity() {
    private lateinit var plateWebView: PlateWebView
    private lateinit var playBilling: PlayBilling
    private lateinit var manualAction: Button
    private lateinit var barcodeAction: Button
    private lateinit var photoAction: Button
    private lateinit var backCallback: OnBackPressedCallback
    private var backRequestInFlight = false

    private val barcodeScanner = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val barcode = result.data?.getStringExtra(BarcodeScanActivity.EXTRA_BARCODE)
        // An empty value includes cancellation and permission denial. The web
        // interface treats it as a dismissed scan instead of showing an error.
        plateWebView.deliverBarcode(barcode.orEmpty())
    }

    private val backupExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) plateWebView.deliverBackupCancelled("export")
        else plateWebView.exportBackup(uri)
    }

    private val backupImporter = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) plateWebView.deliverBackupCancelled("import")
        else plateWebView.importBackup(uri)
    }

    /**
     * A WebView file chooser must always be answered, including on refusal: a
     * callback left unresolved jams that <input> for the rest of the session,
     * so every path below ends in [settleFileChooser].
     */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraOutput: Uri? = null

    private val photoCapture = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { taken ->
        settleFileChooser(if (taken) pendingCameraOutput else null)
        pendingCameraOutput = null
    }

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> settleFileChooser(uri) }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera() else settleFileChooser(null)
    }

    private fun settleFileChooser(uri: Uri?) {
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        callback.onReceiveValue(if (uri == null) null else arrayOf(uri))
    }

    /** True once the chooser has been taken over; the WebView then waits for
     * [settleFileChooser] rather than showing its own (absent) picker. */
    private fun showFileChooser(callback: ValueCallback<Array<Uri>>, wantsCamera: Boolean): Boolean {
        // A second request supersedes the first, which must still be answered.
        settleFileChooser(null)
        fileChooserCallback = callback
        if (!wantsCamera) {
            photoPicker.launch("image/*")
            return true
        }
        // The manifest declares CAMERA, so the capture intent is refused unless
        // the permission has actually been granted.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        return true
    }

    /** The photograph is written into app-private cache storage and handed to
     * the camera through a FileProvider grant, so no broad media permission is
     * involved and the file never lands in the shared gallery. */
    private fun launchCamera() {
        val directory = File(cacheDir, "camera").apply { mkdirs() }
        val target = File(directory, "capture-${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        }.getOrNull()
        if (uri == null) {
            settleFileChooser(null)
            return
        }
        pendingCameraOutput = uri
        photoCapture.launch(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playBilling = PlayBilling(applicationContext)
        plateWebView = PlateWebView(
            context = this,
            onBarcodeScanRequested = {
                barcodeScanner.launch(Intent(this, BarcodeScanActivity::class.java))
            },
            onBackupExportRequested = {
                backupExporter.launch("plate-backup-${System.currentTimeMillis()}.zip")
            },
            onBackupImportRequested = {
                backupImporter.launch(arrayOf("application/zip", "application/x-zip-compressed"))
            },
            onPrimaryActionLabelsChanged = { manual, barcode, photo ->
                // JavaScript-interface calls are not made on the UI thread.
                runOnUiThread { updatePrimaryActionLabels(manual, barcode, photo) }
            },
            onAiAccessRequested = { _, requestId ->
                // The page asks for a narrow entitlement result. It never sees
                // a Play purchase token, account identity, or billing details.
                runOnUiThread {
                    playBilling.requestAiAccess { response ->
                        plateWebView.deliverAiAccessResult(requestId, response.toJson())
                    }
                }
            },
            onAiOfferPurchaseRequested = { basePlanId, requestId ->
                runOnUiThread {
                    playBilling.buyAiOffer(this, basePlanId) { response ->
                        plateWebView.deliverAiAccessResult(requestId, response.toJson())
                    }
                }
            },
            onAiPurchaseRefreshRequested = {
                runOnUiThread {
                    playBilling.refresh { status ->
                        plateWebView.deliverAiPurchaseRefreshResult(status.wireValue)
                    }
                }
            },
            onAiSubscriptionManagementRequested = {
                runOnUiThread { playBilling.manageSubscription(this) }
            },
            onFileChooserRequested = ::showFileChooser,
        )
        val root = FrameLayout(this)
        root.addView(plateWebView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        root.addView(nativeActionBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // evaluateJavascript returns asynchronously. Ignore a second
                // gesture until the page has either unwound its top screen or
                // told us that the day view is already at its root.
                if (backRequestInFlight) return
                backRequestInFlight = true
                plateWebView.navigateBack { handled ->
                    backRequestInFlight = false
                    if (handled) return@navigateBack

                    // No app screen remains. Hand this one gesture back to
                    // Android so its normal root-screen behavior still works.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        playBilling.start()
    }

    override fun onResume() {
        super.onResume()
        // A pending payment may have completed while the activity was away.
        playBilling.refresh()
    }

    override fun onDestroy() {
        if (::playBilling.isInitialized) playBilling.close()
        super.onDestroy()
    }

    /**
     * The PWA's 3D deck animation can make a fixed DOM bar blink in WebView.
     * This small native sibling keeps the exact three entry points stable while
     * delegating their actual behaviour back to the packaged PWA.
     */
    private fun nativeActionBar(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.rgb(250, 246, 239))
        elevation = dp(8).toFloat()

        val rail = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(if (isTablet) 16 else 12), dp(8), dp(if (isTablet) 16 else 12), dp(10))

            manualAction = actionButton("Manual", R.drawable.ic_action_manual, false, "manual")
            barcodeAction = actionButton("Barcode", R.drawable.ic_action_barcode, false, "barcode")
            photoAction = actionButton("Photo", R.drawable.ic_action_photo_locked, true, "photo", locked = true)
            addView(manualAction, actionLayoutParams())
            addView(barcodeAction, actionLayoutParams())
            addView(photoAction, actionLayoutParams())
        }
        addView(rail, FrameLayout.LayoutParams(actionRailWidth(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
    }

    private val isTablet: Boolean
        get() = resources.configuration.smallestScreenWidthDp >= 600

    private fun actionRailWidth(): Int {
        if (!isTablet) return FrameLayout.LayoutParams.MATCH_PARENT
        // A 600dp portrait tablet still needs side gutters, while a wide
        // landscape display should not turn the three actions into a runway.
        return dp(minOf(960, (resources.configuration.screenWidthDp - 48).coerceAtLeast(0)))
    }

    private fun actionLayoutParams() = LinearLayout.LayoutParams(0, dp(if (isTablet) 72 else 64), 1f).apply {
        marginStart = dp(if (isTablet) 6 else 4)
        marginEnd = dp(if (isTablet) 6 else 4)
    }

    private fun actionButton(label: String, icon: Int, primary: Boolean, action: String, locked: Boolean = false): Button = Button(this).apply {
        text = label
        textSize = if (isTablet) 14f else 12f
        isAllCaps = false
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else Color.rgb(74, 87, 76))
        compoundDrawablePadding = dp(2)
        // The locked photo drawable carries its own two colours: the lock sits
        // on an accent-coloured cutout over the camera. Tinting it would flatten
        // that into an indistinct blob.
        val image = getDrawable(icon)?.mutate()?.apply {
            if (!locked) setTint(if (primary) Color.WHITE else Color.rgb(74, 87, 76))
        }
        setCompoundDrawablesWithIntrinsicBounds(null, image, null, null)
        background = GradientDrawable().apply {
            setColor(if (primary) Color.rgb(46, 139, 87) else Color.WHITE)
            cornerRadius = dp(if (isTablet) 18 else 16).toFloat()
            setStroke(dp(1), if (primary) Color.rgb(35, 111, 68) else Color.rgb(232, 224, 210))
        }
        minWidth = 0
        minHeight = 0
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setOnClickListener { plateWebView.performPrimaryAction(action) }
    }

    private fun updatePrimaryActionLabels(manual: String, barcode: String, photo: String) {
        // A WebView page can finish loading while Android restores an activity.
        // The buttons are already added before its scripts send this message.
        manualAction.text = manual
        barcodeAction.text = barcode
        photoAction.text = photo
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
