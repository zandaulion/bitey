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
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Hosts the locally packaged Plate interface. The WebView is a presentation
 * layer only: native code will own persistence, camera/barcode access and the
 * narrowly-scoped network calls rather than relying on a web server.
 */
/** The page's phone column, `.view { max-width: 560px }` in app.css. */
private const val PHONE_COLUMN_DP = 560

/** The published privacy policy, the same address given in Play Console. Its
 * source is store/PRIVACY_NOTICE.md. */
private const val PRIVACY_NOTICE_URL = "https://zandaulion.com/plate-privacy.html"

class MainActivity : ComponentActivity() {
    private lateinit var plateWebView: PlateWebView
    private lateinit var playBilling: PlayBilling

    /** A reading can take most of a minute. It gets its own thread so it
     * never queues behind -- or blocks -- a barcode lookup. */
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Lazy: the application context does not exist yet while fields are built.
    private val analysis by lazy { BiteyAnalysis(applicationContext) }
    private lateinit var manualAction: Button
    private lateinit var barcodeAction: Button
    private lateinit var photoAction: Button

    /** The Manual / Barcode / Photo bar. Hidden while the page has a sheet
     * open, which the page reports through the bridge. */
    private lateinit var actionBar: View
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
    private var pendingCameraFile: File? = null

    /**
     * Set while the page, rather than a file input, is waiting for a
     * photograph. The two routes cannot overlap, because either way Android is
     * showing another activity until the result arrives.
     */
    private var captureRequestId: String? = null

    private val photoCapture = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { taken ->
        val output = pendingCameraOutput
        val file = pendingCameraFile
        pendingCameraOutput = null
        pendingCameraFile = null
        if (captureRequestId != null) {
            deliverCapture(if (taken && file != null) file.inputStream() else null, "image/jpeg")
            file?.delete()
        } else {
            settleFileChooser(if (taken) output else null)
        }
    }

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (captureRequestId == null) {
            settleFileChooser(uri)
            return@registerForActivityResult
        }
        val stream = uri?.let { contentResolver.openInputStream(it) }
        deliverCapture(stream, uri?.let(contentResolver::getType) ?: "image/jpeg")
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera()
        else if (captureRequestId != null) deliverCapture(null, "image/jpeg")
        else settleFileChooser(null)
    }

    private fun settleFileChooser(uri: Uri?) {
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        callback.onReceiveValue(if (uri == null) null else arrayOf(uri))
    }

    /** The page is told a path to fetch, or that nothing arrived. Every route
     * out of a capture answers exactly once: an unanswered request would leave
     * the photo button dead until the app restarted. */
    private fun deliverCapture(source: InputStream?, mimeType: String?) {
        val requestId = captureRequestId ?: return
        captureRequestId = null
        val id = source?.let {
            runCatching {
                plateWebView.captures.stage(it, mimeType.orEmpty().substringBefore(';'))
            }.getOrNull()
        }
        val payload = JSONObject()
            .put("status", if (id == null) "cancelled" else "ok")
            .apply { if (id != null) put("id", id) }
            .toString()
        plateWebView.deliverCaptureResult(requestId, payload)
    }

    /** Opened by native code because a WebView file chooser needs a user
     * gesture the entitlement check has already spent. */
    private fun startCapture(source: String, requestId: String) {
        runOnUiThread {
            // A request already waiting is answered before it is replaced.
            deliverCapture(null, null)
            settleFileChooser(null)
            captureRequestId = requestId
            if (source == "gallery") {
                photoPicker.launch("image/*")
                return@runOnUiThread
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchCamera()
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
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
            if (captureRequestId != null) deliverCapture(null, null) else settleFileChooser(null)
            return
        }
        pendingCameraOutput = uri
        pendingCameraFile = target
        photoCapture.launch(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything can ask for a token: the analysis request carries
        // one from the first photograph on.
        AppCheckSetup.install(applicationContext)

        // Play's answer arrives on its own thread, on every resume (once the
        // person has used Bitey AI) and after a purchase; the padlocks follow
        // it. Creating the client does not connect to Play.
        playBilling = PlayBilling(applicationContext) { status ->
            runOnUiThread { applyAiEntitlement(status) }
        }
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
            aiEntitlementStatus = { playBilling.status().wireValue },
            onPrimaryActionsVisibilityChanged = { visible ->
                runOnUiThread {
                    // GONE rather than INVISIBLE, so the WebView beneath gets
                    // the whole screen back and a sheet's footer is reachable.
                    if (::actionBar.isInitialized) {
                        actionBar.visibility = if (visible) View.VISIBLE else View.GONE
                    }
                }
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
                    playBilling.restore { status ->
                        plateWebView.deliverAiPurchaseRefreshResult(status.wireValue)
                    }
                }
            },
            onAiSubscriptionManagementRequested = {
                runOnUiThread { playBilling.manageSubscription(this) }
            },
            onCaptureRequested = ::startCapture,
            onAnalysisRequested = { payload, requestId ->
                // The token is read at send time rather than captured when the
                // page asked, so a subscription that lapsed in between is not
                // presented. The server re-verifies regardless.
                analysisExecutor.execute {
                    val result = analysis.analyse(playBilling.activePurchaseToken(), payload)
                    plateWebView.deliverAnalysisResult(requestId, result)
                }
            },
            onPrivacyNoticeRequested = {
                runOnUiThread {
                    // The WebView refuses to leave its own origin, so the
                    // policy opens in the person's browser. A phone with no
                    // browser at all simply does nothing rather than crash.
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_NOTICE_URL)))
                    }
                }
            },
            onFileChooserRequested = ::showFileChooser,
        )
        val root = FrameLayout(this)
        root.addView(plateWebView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        actionBar = nativeActionBar()
        // Play may already have answered before the bar existed.
        applyAiEntitlement(playBilling.status())
        root.addView(actionBar, FrameLayout.LayoutParams(
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

    override fun onResume() {
        super.onResume()
        // A pending payment may have completed while the activity was away.
        // Resume also runs at launch, so this is the only start-up query;
        // a second one from onStart raced it into two connections.
        playBilling.refreshIfEngaged()
    }

    override fun onDestroy() {
        if (::playBilling.isInitialized) playBilling.close()
        analysisExecutor.shutdownNow()
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
        if (!isTablet) {
            // A phone turned sideways keeps the page's centred 560-wide column
            // (see the tablet media queries in app.css), so the rail matches it
            // rather than stretching three buttons across the whole screen. In
            // portrait a phone is narrower than this and the rail fills it.
            val width = resources.configuration.screenWidthDp
            return if (width > PHONE_COLUMN_DP) dp(PHONE_COLUMN_DP) else FrameLayout.LayoutParams.MATCH_PARENT
        }
        // A 600dp portrait tablet still needs side gutters, while a wide
        // landscape display should not turn the three actions into a runway.
        return dp(minOf(960, (resources.configuration.screenWidthDp - 48).coerceAtLeast(0)))
    }

    private fun actionLayoutParams() = LinearLayout.LayoutParams(0, dp(if (isTablet) 72 else 64), 1f).apply {
        marginStart = dp(if (isTablet) 6 else 4)
        marginEnd = dp(if (isTablet) 6 else 4)
    }

    /**
     * Shows or hides the padlocks for Bitey AI, natively and in the page.
     *
     * Only an active subscription unlocks them. Pending is still locked: the
     * payment has not gone through, and Play may yet decline it.
     */
    private fun applyAiEntitlement(status: PlayBilling.Status) {
        val locked = status != PlayBilling.Status.ACTIVE
        if (::photoAction.isInitialized) {
            photoAction.setCompoundDrawables(null, sized(photoIcon(locked)), null, null)
        }
        if (::plateWebView.isInitialized) plateWebView.deliverAiEntitlement(status.wireValue)
    }

    /** The locked icon carries its own two colours (a white lock on a green
     * cutout) and must not be tinted; the plain camera is tinted white to sit
     * on the green primary button. */
    private fun photoIcon(locked: Boolean) = getDrawable(
        if (locked) R.drawable.ic_action_photo_locked else R.drawable.ic_action_photo,
    )?.mutate()?.apply { if (!locked) setTint(Color.WHITE) }

    /**
     * The icons are 24dp vectors, Android's default, which left them looking
     * lost in buttons 64dp tall. Drawn at 30dp (34 on a tablet) they fill the
     * button in proportion, and stay sharp because they are vectors.
     *
     * The height budget still holds at large system font sizes: 30 + 2 gap +
     * a 12sp label at 1.3x + 8 padding is 61, inside the 64dp button.
     */
    private fun sized(drawable: android.graphics.drawable.Drawable?) = drawable?.apply {
        val size = dp(if (isTablet) 34 else 30)
        setBounds(0, 0, size, size)
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
        setCompoundDrawables(null, sized(image), null, null)
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
