package com.zandaulion.bitey

import android.Manifest
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.webkit.ValueCallback
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
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

/** Width of the landscape side rail: an 80dp button and its 8dp gutters. */
private const val SIDE_RAIL_DP = 96

/** Four 64dp buttons, their gaps and the rail's padding. */
private const val SIDE_RAIL_HEIGHT_DP = 304

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
    private lateinit var galleryAction: Button
    private lateinit var photoAction: Button

    /** The Manual / Barcode / Photo bar. Hidden while the page has a sheet
     * open, which the page reports through the bridge. */
    private lateinit var actionBar: View
    private lateinit var backCallback: OnBackPressedCallback
    private var backRequestInFlight = false

    /** True once the page has sent its action labels, which it does when its
     * scripts are running; before that an action has nowhere to go. */
    private var pageReady = false

    /** An action from a shortcut or the tile that arrived before the page. */
    private var pendingLaunchAction: String? = null

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
    /** True when the server turned a reading down because the subscription
     * is not active (403 not_entitled), rather than for anything in it. */
    private fun subscriptionRefused(result: String): Boolean = runCatching {
        val answer = JSONObject(result)
        answer.optInt("httpStatus") == 403 &&
            answer.optJSONObject("body")?.optString("error") == "not_entitled"
    }.getOrDefault(false)

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
            onBarcodeScanRequested = { labels ->
                barcodeScanner.launch(
                    Intent(this, BarcodeScanActivity::class.java)
                        .putExtra(BarcodeScanActivity.EXTRA_LABELS, labels),
                )
            },
            onBackupExportRequested = {
                backupExporter.launch("plate-backup-${System.currentTimeMillis()}.zip")
            },
            onBackupImportRequested = {
                backupImporter.launch(arrayOf("application/zip", "application/x-zip-compressed"))
            },
            onPrimaryActionLabelsChanged = { manual, barcode, gallery, photo ->
                // JavaScript-interface calls are not made on the UI thread.
                runOnUiThread {
                    updatePrimaryActionLabels(manual, barcode, gallery, photo)
                    LaunchActions.publish(applicationContext, manual, barcode, gallery, photo)
                    pageReady = true
                    pendingLaunchAction?.let {
                        pendingLaunchAction = null
                        plateWebView.performPrimaryAction(it)
                    }
                }
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
                    // Just after start-up Play may not have answered yet; a
                    // short wait beats refusing a subscriber.
                    val started = SystemClock.elapsedRealtime()
                    val token = playBilling.awaitPurchaseToken(5_000)
                    Log.i("BiteyAnalysis", "purchase token ${if (token != null) "ready" else "absent"} " +
                        "after ${SystemClock.elapsedRealtime() - started} ms")
                    val result = analysis.analyse(token, payload)
                    // Refused by the server for the subscription, not for the
                    // photo: Play on the phone is behind, so stop presenting it.
                    if (subscriptionRefused(result)) playBilling.serverRefused(token)
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
            quickTileSupported = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
            onQuickTileRequested = {
                runOnUiThread {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestPhotoTile()
                    else plateWebView.deliverQuickTileResult("failed")
                }
            },
            onWidgetSnapshot = { json -> TodayWidget.save(applicationContext, json) },
            onWidgetBitey = { png -> TodayWidget.saveBitey(applicationContext, png) },
            widgetPinSupported = {
                AppWidgetManager.getInstance(this).isRequestPinAppWidgetSupported
            },
            onWidgetPinRequested = { runOnUiThread { requestWidgetPin() } },
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
        root.addView(actionBar, actionBarLayoutParams())
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
        // Not after a rotation or process restore: the action was already run
        // by the activity this one replaces.
        if (savedInstanceState == null) runLaunchAction(intent)
    }

    /** A shortcut or the tile while Bitey is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runLaunchAction(intent)
    }

    /**
     * Asks Android to put the Meal photo tile in Quick Settings. Android shows
     * its own prompt with the tile's name and icon, and nothing is added
     * without the person's yes; the answer goes back to the page.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestPhotoTile() {
        val statusBar = getSystemService(StatusBarManager::class.java)
        if (statusBar == null) {
            plateWebView.deliverQuickTileResult("failed")
            return
        }
        runCatching {
            statusBar.requestAddTileService(
                ComponentName(this, PhotoTileService::class.java),
                getString(R.string.tile_photo),
                Icon.createWithResource(this, R.drawable.ic_tile_bitey),
                mainExecutor,
            ) { code ->
                plateWebView.deliverQuickTileResult(
                    when (code) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "added"
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "already"
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "declined"
                        else -> "failed"
                    },
                )
            }
        }.onFailure { plateWebView.deliverQuickTileResult("failed") }
    }

    /**
     * Asks the launcher to place the Today widget. The launcher shows its own
     * prompt and nothing is placed without a yes; one already on the home
     * screen is reported rather than doubled.
     */
    private fun requestWidgetPin() {
        val manager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, TodayWidget::class.java)
        val result = when {
            manager.getAppWidgetIds(provider).isNotEmpty() -> "already"
            !manager.isRequestPinAppWidgetSupported -> "failed"
            runCatching { manager.requestPinAppWidget(provider, null, null) }.getOrDefault(false) -> "requested"
            else -> "failed"
        }
        plateWebView.deliverWidgetPinResult(result)
    }

    private fun runLaunchAction(intent: Intent?) {
        val action = LaunchActions.from(intent) ?: return
        // Consumed once: a later recreation must not open the camera again.
        intent?.removeExtra(LaunchActions.EXTRA_ACTION)
        if (pageReady) plateWebView.performPrimaryAction(action)
        else pendingLaunchAction = action
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
    /**
     * Where the three logging actions sit.
     *
     * A phone turned sideways has little height to spare: a full bottom bar
     * there covered the middle of the day's total card (PrimeTestLab report
     * 7991, M-02). The page keeps a centred 560dp column, so a phone wide
     * enough to leave room beside it gets the actions as a rail in that empty
     * margin, covering nothing. A narrower one keeps them along the bottom,
     * but compact: icon beside label, a third of the height shorter.
     * Portrait and tablets are unchanged. The activity is rebuilt on each
     * rotation, so this is decided afresh every time.
     */
    private enum class RailLayout { BOTTOM, BOTTOM_COMPACT, SIDE }

    private val railLayout: RailLayout by lazy {
        val config = resources.configuration
        when {
            isTablet || config.orientation != Configuration.ORIENTATION_LANDSCAPE -> RailLayout.BOTTOM
            (config.screenWidthDp - PHONE_COLUMN_DP) / 2 >= SIDE_RAIL_DP &&
                config.screenHeightDp >= SIDE_RAIL_HEIGHT_DP -> RailLayout.SIDE
            else -> RailLayout.BOTTOM_COMPACT
        }
    }

    private fun nativeActionBar(): View = FrameLayout(this).apply {
        val layout = railLayout
        if (layout == RailLayout.SIDE) {
            // Nothing behind the buttons but the page's own empty margin, so
            // no strip of colour or shadow; touches between the buttons fall
            // through to the page.
            applySideInsets(this)
        } else {
            setBackgroundColor(Color.rgb(250, 246, 239))
            elevation = dp(8).toFloat()
        }

        val rail = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER
            when (layout) {
                RailLayout.SIDE -> {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                RailLayout.BOTTOM_COMPACT -> {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                }
                RailLayout.BOTTOM -> {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(if (isTablet) 16 else 12), dp(8), dp(if (isTablet) 16 else 12), dp(10))
                }
            }

            manualAction = actionButton("Type", R.drawable.ic_action_manual, false, "manual")
            barcodeAction = actionButton("Barcode", R.drawable.ic_action_barcode, false, "barcode")
            // The gallery beside the camera rather than a chooser in front of
            // it: both stay one tap (PrimeTestLab report 7991, S-01). It used
            // to be reachable only from "Already have a photo?" at the foot
            // of the day, below every meal.
            galleryAction = actionButton("Gallery", R.drawable.ic_action_gallery_locked, false, "gallery", locked = true)
            photoAction = actionButton("Photo", R.drawable.ic_action_photo_locked, true, "photo", locked = true)
            addView(manualAction, actionLayoutParams())
            addView(barcodeAction, actionLayoutParams())
            addView(galleryAction, actionLayoutParams())
            addView(photoAction, actionLayoutParams())
        }
        if (layout == RailLayout.SIDE) {
            addView(rail, FrameLayout.LayoutParams(dp(SIDE_RAIL_DP), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        } else {
            addView(rail, FrameLayout.LayoutParams(actionRailWidth(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
    }

    /** Where the bar itself goes in the activity's root. */
    private fun actionBarLayoutParams() = if (railLayout == RailLayout.SIDE) {
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END,
        )
    } else {
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        )
    }

    /** Sideways, the navigation bar or a camera cutout can be on either long
     * edge; the rail steps in from whichever it meets. */
    private fun applySideInsets(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            val (left, right) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                bars.left to bars.right
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetLeft to insets.systemWindowInsetRight
            }
            v.setPadding(left, 0, right, 0)
            insets
        }
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

    private fun actionLayoutParams() = when (railLayout) {
        RailLayout.SIDE -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
        RailLayout.BOTTOM_COMPACT -> LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
        RailLayout.BOTTOM -> LinearLayout.LayoutParams(0, dp(if (isTablet) 72 else 64), 1f).apply {
            marginStart = dp(if (isTablet) 6 else 4)
            marginEnd = dp(if (isTablet) 6 else 4)
        }
    }

    /** The compact bar puts the icon beside the label, which is what saves
     * its height; everywhere else it sits above. */
    private fun Button.placeIcon(icon: android.graphics.drawable.Drawable?) {
        if (railLayout == RailLayout.BOTTOM_COMPACT) {
            compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelative(sized(icon), null, null, null)
        } else {
            setCompoundDrawables(null, sized(icon), null, null)
        }
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
            photoAction.placeIcon(photoIcon(locked))
            galleryAction.placeIcon(galleryIcon(locked))
        }
        if (::plateWebView.isInitialized) plateWebView.deliverAiEntitlement(status.wireValue)
    }

    /** The locked icon carries its own two colours (a white lock on a green
     * cutout) and must not be tinted; the plain camera is tinted white to sit
     * on the green primary button. */
    private fun photoIcon(locked: Boolean) = getDrawable(
        if (locked) R.drawable.ic_action_photo_locked else R.drawable.ic_action_photo,
    )?.mutate()?.apply { if (!locked) setTint(Color.WHITE) }

    /** The gallery sits on a white button, so its open icon takes the
     * secondary ink; the locked one carries its own colours. */
    private fun galleryIcon(locked: Boolean) = getDrawable(
        if (locked) R.drawable.ic_action_gallery_locked else R.drawable.ic_action_gallery,
    )?.mutate()?.apply { if (!locked) setTint(Color.rgb(74, 87, 76)) }

    /**
     * The icons are 24dp vectors, Android's default, which left them looking
     * lost in buttons 64dp tall. Drawn at 30dp (34 on a tablet) they fill the
     * button in proportion, and stay sharp because they are vectors.
     *
     * The height budget still holds at large system font sizes: 30 + 2 gap +
     * a 12sp label at 1.3x + 8 padding is 61, inside the 64dp button. The
     * compact bar's icon sits beside its label at the standard 24dp.
     */
    private fun sized(drawable: android.graphics.drawable.Drawable?) = drawable?.apply {
        val size = dp(if (railLayout == RailLayout.BOTTOM_COMPACT) 24 else if (isTablet) 34 else 30)
        setBounds(0, 0, size, size)
    }

    private fun actionButton(label: String, icon: Int, primary: Boolean, action: String, locked: Boolean = false): Button = Button(this).apply {
        text = label
        textSize = if (isTablet) 14f else 12f
        isAllCaps = false
        gravity = Gravity.CENTER
        // Four buttons share a narrow phone's width, and some languages'
        // labels are long ("Code-barres"). One line, shrinking to fit,
        // rather than wrapping out of the button.
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(8, if (isTablet) 14 else 12, 1, TypedValue.COMPLEX_UNIT_SP)
        setTextColor(if (primary) Color.WHITE else Color.rgb(74, 87, 76))
        compoundDrawablePadding = dp(2)
        // The locked photo drawable carries its own two colours: the lock sits
        // on an accent-coloured cutout over the camera. Tinting it would flatten
        // that into an indistinct blob.
        val image = getDrawable(icon)?.mutate()?.apply {
            if (!locked) setTint(if (primary) Color.WHITE else Color.rgb(74, 87, 76))
        }
        placeIcon(image)
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

    private fun updatePrimaryActionLabels(manual: String, barcode: String, gallery: String, photo: String) {
        // A WebView page can finish loading while Android restores an activity.
        // The buttons are already added before its scripts send this message.
        manualAction.text = manual
        barcodeAction.text = barcode
        galleryAction.text = gallery
        photoAction.text = photo
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
