package com.glasscast.sender

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class MainActivity : Activity() {
    private lateinit var codeInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var primaryButton: Button
    private lateinit var sessionSummary: TextView
    private lateinit var readySummary: TextView
    private lateinit var readyCard: TextView
    private lateinit var nowPlayingText: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var durationText: TextView
    private lateinit var timelineSeekBar: SeekBar
    private lateinit var timelineStatusText: TextView
    private lateinit var seekBackButton: Button
    private lateinit var seekForwardButton: Button
    private lateinit var captionsButton: Button
    private lateinit var captionsStatusText: TextView
    private lateinit var statusText: TextView
    private lateinit var lastSentUrlText: TextView
    private lateinit var lastResponseText: TextView
    private lateinit var lastCommandText: TextView
    private lateinit var debugToggleText: TextView
    private lateinit var debugDetailsContainer: LinearLayout
    private lateinit var setupGuideContainer: LinearLayout
    private lateinit var setupCopyStatusText: TextView
    private lateinit var setupFallbackActionsContainer: LinearLayout
    private lateinit var manualFallbackBody: LinearLayout
    private lateinit var localVideoButton: Button
    private lateinit var localVideoStatusText: TextView
    private lateinit var localVideoDebugText: TextView

    private var videoUrl = ""
    private var lastSentUrl = ""
    private var lastResponse = ""
    private var localVideoUri: Uri? = null
    private var localVideoUrl: String? = null
    private var localVideoHealthUrl: String? = null
    private var localVideoName = ""
    private var localVideoLength = -1L
    private var openedFromShare = false
    private var suppressCodeWatcher = false
    private var suppressUrlWatcher = false
    private var isPolling = false
    private var isUserScrubbing = false
    private var timelineSeekAvailable = false
    private var timelineDurationSeconds = 0.0
    private var localScrubSeconds = 0.0
    private var ignorePlaybackPositionUntilMs = 0L
    private var optimisticCurrentSeconds: Double? = null
    private var captionsEnabled: Boolean? = null
    private var captionsAvailable = false
    private var pendingCaptionsEnabled: Boolean? = null
    private var captionsPendingUntilMs = 0L
    private val captionsPendingTimeout = Runnable {
        if (pendingCaptionsEnabled == null) return@Runnable
        clearCaptionPendingState()
        renderCaptionState()
    }

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val client = OkHttpClient()
    private val localVideoServer by lazy { LocalVideoServer(this) }
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val pollHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isPolling) return
            fetchPlaybackState()
            pollHandler.postDelayed(this, PLAYBACK_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        setSessionCode(prefs.getString(KEY_SESSION_CODE, "").orEmpty())
        updateSessionSummary()
        showStatus("Ready")
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        startPlaybackPolling()
    }

    override fun onStop() {
        isUserScrubbing = false
        stopPlaybackPolling()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            if (ENABLE_LOCAL_VIDEO_EXPERIMENT) {
                localVideoServer.stop()
                LocalVideoKeepAliveService.stop(this)
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API, retained here to avoid adding activity-result dependencies.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_LOCAL_VIDEO_REQUEST || resultCode != RESULT_OK) return
        if (!ENABLE_LOCAL_VIDEO_EXPERIMENT) {
            showUnsupportedShareType()
            return
        }
        val uri = data?.data
        if (uri == null) {
            showStatus("Could not open selected video", isError = true)
            return
        }
        takeReadPermission(uri, data.flags)
        prepareLocalVideo(uri, castAfterReady = true, openedFromLocalShare = false)
    }

    private fun buildUi() {
        val root = ScrollView(this).apply {
            setBackgroundColor(color(BG))
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(BASE_CONTENT_TOP_PADDING), dp(24), dp(28))
        }
        root.addView(content)
        applySystemInsetPadding(content)

        content.addView(brandHeader())

        content.addView(setupEntryCard())
        setupGuideContainer = setupGuide().apply {
            visibility = View.GONE
        }
        content.addView(setupGuideContainer)

        content.addView(sectionTitle("Session code"))
        sessionSummary = TextView(this).apply {
            setTextColor(color(TEXT))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        }
        content.addView(sessionSummary)

        readySummary = TextView(this).apply {
            setTextColor(color(MUTED))
            textSize = 15f
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(readySummary)

        readyCard = TextView(this).apply {
            text = "Ready to cast"
            setTextColor(color(BG))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedDrawable(SUCCESS, radiusDp = 18, strokeColor = MINT, strokeDp = 1)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        content.addView(readyCard)

        codeInput = input("ABC123").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setSingleLine(true)
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    if (suppressCodeWatcher) return
                    prefs.edit().putString(KEY_SESSION_CODE, normalizeSessionCode(s?.toString())).apply()
                    updateSessionSummary()
                    restartPlaybackPolling()
                }
            })
        }
        content.addView(codeInput)
        content.addView(button("Save or Pair") { saveSessionCode() })

        content.addView(label("Video link"))
        content.addView(helper("Paste or share a supported video link."))
        content.addView(helper("Some videos may block playback or timeline controls."))
        urlInput = input("https://www.youtube.com/watch?v=...").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(false)
            minLines = 2
            maxLines = 4
            addTextChangedListener(object : SimpleTextWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    if (suppressUrlWatcher) return
                    videoUrl = s?.toString()?.trim().orEmpty()
                    updateSessionSummary()
                }
            })
        }
        content.addView(urlInput)

        primaryButton = button("Cast Video", large = true) { castVideo() }
        content.addView(primaryButton)

        if (ENABLE_LOCAL_VIDEO_EXPERIMENT) {
            localVideoButton = button("Cast Local Video", large = true) { castLocalVideoOrPick() }
            content.addView(localVideoButton)
            if (BuildConfig.DEBUG) {
                content.addView(button("Open Health URL") { openLocalHealthUrl() })
                content.addView(button("Copy Health URL") { copyLocalHealthUrl() })
                content.addView(button("Open Video URL") { openLocalVideoUrl() })
                content.addView(button("Copy Video URL") { copyLocalVideoUrl() })
            }
            content.addView(button("Stop Local Video") { stopLocalVideoServing() })
            localVideoStatusText = TextView(this).apply {
                text = ""
                setTextColor(color(MUTED))
                textSize = 14f
                setPadding(0, dp(8), 0, dp(2))
            }
            content.addView(localVideoStatusText)
            if (BuildConfig.DEBUG) {
                content.addView(localVideoDebugSection())
            }
        }

        content.addView(TextView(this).apply {
            text = "Timeline"
            setTextColor(color(MUTED))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(8))
        })

        nowPlayingText = TextView(this).apply {
            text = "Now playing"
            setTextColor(color(TEXT))
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(6))
        }
        content.addView(nowPlayingText)

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        currentTimeText = TextView(this).apply {
            text = "0:00"
            setTextColor(color(MUTED))
            textSize = 14f
        }
        timeRow.addView(currentTimeText)

        timelineSeekBar = SeekBar(this).apply {
            max = SEEK_BAR_MAX
            progress = 0
            isEnabled = false
            progressTintList = ColorStateList.valueOf(color(PRIMARY))
            progressBackgroundTintList = ColorStateList.valueOf(color(NIGHT_BLUE))
            thumbTintList = ColorStateList.valueOf(color(STAR_GOLD))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || timelineDurationSeconds <= 0.0) return
                    localScrubSeconds = clampTime(progressToSeconds(progress), timelineDurationSeconds)
                    currentTimeText.text = formatTime(localScrubSeconds)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserScrubbing = true
                    localScrubSeconds = progressToSeconds(seekBar?.progress ?: 0)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (!timelineSeekAvailable) {
                        isUserScrubbing = false
                        showTimelineUnavailable(TIMELINE_UNAVAILABLE_FOR_PLAYER)
                        return
                    }
                    val seconds = clampTime(progressToSeconds(seekBar?.progress ?: 0), timelineDurationSeconds)
                    localScrubSeconds = seconds
                    currentTimeText.text = formatTime(seconds)
                    sendSeekTo(seconds)
                }
            })
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
            }
        }
        timeRow.addView(timelineSeekBar)

        durationText = TextView(this).apply {
            text = "--:--"
            setTextColor(color(MUTED))
            textSize = 14f
            gravity = Gravity.END
        }
        timeRow.addView(durationText)
        content.addView(timeRow)

        timelineStatusText = TextView(this).apply {
            text = TIMELINE_UNAVAILABLE_FOR_PLAYER
            setTextColor(color(MUTED))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        content.addView(timelineStatusText)

        content.addView(TextView(this).apply {
            text = "Playback"
            setTextColor(color(MUTED))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(8))
        })

        content.addView(button("Play/Pause") { sendPlaybackCommand("playPause") })
        seekBackButton = button("Seek -10s") { sendTimelineCommand("seekBack") }
        content.addView(seekBackButton)
        seekForwardButton = button("Seek +10s") { sendTimelineCommand("seekForward") }
        content.addView(seekForwardButton)
        setTimelineButtonsEnabled(false)
        captionsButton = button("Captions") { toggleCaptions() }
        content.addView(captionsButton)
        captionsStatusText = TextView(this).apply {
            text = CAPTIONS_NOT_AVAILABLE
            setTextColor(color(MUTED))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        content.addView(captionsStatusText)
        renderCaptionState()
        content.addView(button("Stop") { sendPlaybackCommand("stop") })

        statusText = TextView(this).apply {
            text = ""
            setTextColor(color(MUTED))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedDrawable(SURFACE, radiusDp = 16, strokeColor = NIGHT_BLUE, strokeDp = 1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
            }
        }
        content.addView(statusText)

        content.addView(aboutSettingsSection())
        if (BuildConfig.DEBUG) {
            content.addView(debugDetailsSection())
        }

        setContentView(root)
    }

    private fun brandHeader(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8))

            addView(assetImage(BRAND_LOGO_ASSET, heightDp = 92, contentDescriptionText = "GlassCast").apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
        }

    private fun applySystemInsetPadding(content: View) {
        content.setOnApplyWindowInsetsListener { view, insets ->
            val statusBarTop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            view.setPadding(
                dp(24),
                (statusBarTop + dp(HEADER_BREATHING_ROOM)).coerceAtLeast(dp(BASE_CONTENT_TOP_PADDING)),
                dp(24),
                dp(28)
            )
            insets
        }
        content.requestApplyInsets()
    }

    private fun assetImage(assetPath: String, heightDp: Int, contentDescriptionText: String): ImageView =
        ImageView(this).apply {
            contentDescription = contentDescriptionText
            val bitmap = assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
            setImageBitmap(bitmap)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp)
            )
        }

    private fun setupEntryCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(SURFACE_ALT, radiusDp = 18, strokeColor = SECONDARY, strokeDp = 1)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
                bottomMargin = dp(4)
            }

            addView(TextView(this@MainActivity).apply {
                text = "Set up Meta Display"
                setTextColor(color(TEXT))
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "Open the receiver on your glasses."
                setTextColor(color(MUTED))
                textSize = 15f
                setPadding(0, dp(6), 0, dp(10))
            })
            addView(button("Set up Meta Display") {
                setupGuideContainer.visibility = View.VISIBLE
                setupGuideContainer.requestFocus()
            })
        }

    private fun setupGuide(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(SURFACE, radiusDp = 18, strokeColor = NIGHT_BLUE, strokeDp = 1)
            setPadding(dp(16), dp(18), dp(16), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            }

            // Future auth/license gate should not hide basic setup instructions.
            addView(TextView(this@MainActivity).apply {
                text = "Set up Meta Display"
                setTextColor(color(TEXT))
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "Enable Developer Mode once, then tap the button below to add GlassCast to your Meta Ray-Ban Display glasses."
                setTextColor(color(MUTED))
                textSize = 16f
                setPadding(0, dp(10), 0, dp(12))
            })

            addView(setupSectionTitle("1. Enable Developer Mode"))
            numberedSteps(
                listOf(
                    "Open the Meta AI app.",
                    "Go to Settings.",
                    "Open App Info.",
                    "Tap the app version number five times.",
                    "Enable Developer Mode when prompted.",
                    "Return to Settings."
                )
            ).forEach { addView(it) }
            addView(setupNote("If you do not see Developer Mode, update the Meta AI app and your glasses firmware first."))

            addView(setupSectionTitle("2. Add GlassCast"))
            addView(setupBody("After Developer Mode is enabled, tap the button below. This should open the Meta AI app and start the Web App add flow."))
            addView(button("Add GlassCast to Meta Display") { openMetaAiAddWebAppFlow(this@MainActivity) })
            setupCopyStatusText = TextView(this@MainActivity).apply {
                text = "If Meta AI does not open, make sure the Meta AI app is installed and updated. You can also add GlassCast manually."
                setTextColor(color(MINT))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }
            addView(setupCopyStatusText)
            setupFallbackActionsContainer = metaAiFallbackActions().apply {
                visibility = View.GONE
            }
            addView(setupFallbackActionsContainer)

            addView(setupSectionTitle("3. Pair and cast"))
            numberedSteps(
                listOf(
                    "Open the receiver on your glasses.",
                    "Enter the session code shown on the glasses.",
                    "Paste or share a supported video link.",
                    "Tap Cast Video."
                )
            ).forEach { addView(it) }
            addView(setupNote("Once paired, you can share a YouTube video to GlassCast from the Android share menu."))

            addView(manualFallbackSection())

            addView(collapsibleSection(
                "4. Test before using glasses",
                listOf(
                    "You can test GlassCast in a desktop browser before using the glasses.",
                    "1. Open $RECEIVER_URL on desktop.",
                    "2. Open this Android app on your phone.",
                    "3. Enter the session code shown on the desktop receiver.",
                    "4. Paste or share a YouTube link.",
                    "5. Tap Cast Video.",
                    "6. Test Play/Pause, Seek, Stop, and the timeline.",
                    "7. After desktop testing works, open GlassCast on your glasses.",
                    "",
                    "On a desktop browser, keyboard arrow keys can simulate the glasses D-pad style controls."
                ),
                listOf("View Meta Testing Docs" to META_TEST_DOCS_URL)
            ))

            addView(collapsibleSection(
                "Troubleshooting",
                listOf(
                    "I do not see Developer Mode",
                    "Update the Meta AI app and your glasses firmware. Then open Meta AI app settings, go to App Info, and tap the app version number five times.",
                    "",
                    "Add button does not open Meta AI",
                    "The one-tap add button still requires Developer Mode. If the button does not open Meta AI, use the manual setup steps.",
                    "",
                    "I do not see Web Apps",
                    "Update Meta AI and glasses firmware, then enable Developer Mode. After that, return to App connections.",
                    "",
                    "The receiver URL will not open",
                    "Make sure the URL is exactly $RECEIVER_URL and that your phone or glasses have internet access.",
                    "",
                    "The session code does not work",
                    "Make sure the code in this app matches the code shown on the glasses. If needed, reopen GlassCast on the glasses and try the new code.",
                    "",
                    "YouTube video does not play",
                    "Use a direct YouTube watch or share link. Some private, restricted, age-gated, region-blocked, or non-embeddable videos may not play.",
                    "",
                    "Live video timeline is unavailable",
                    "Live streams may not have a normal timeline. Play/Pause and Stop may still work."
                ),
                emptyList()
            ))

            addView(button("I've added GlassCast") {
                setupGuideContainer.visibility = View.GONE
                showStatus("Ready")
            })
        }

    private fun numberedSteps(steps: List<String>): List<TextView> =
        steps.mapIndexed { index, step ->
            TextView(this).apply {
                text = "${index + 1}. $step"
                setTextColor(color(TEXT))
                textSize = 16f
                setPadding(0, dp(4), 0, dp(4))
            }
        }

    private fun setupSectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(TEXT))
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun setupBody(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(MUTED))
        textSize = 16f
        setPadding(0, 0, 0, dp(8))
    }

    private fun setupNote(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(MINT))
        textSize = 15f
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun collapsibleSection(
        title: String,
        lines: List<String>,
        linkButtons: List<Pair<String, String>> = emptyList()
    ): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(6))
        }
        lines.forEach { line ->
            val isHeading = line in TROUBLESHOOTING_TITLES || line == "Meta Display test:"
            body.addView(TextView(this).apply {
                text = line
                setTextColor(if (isHeading) color(TEXT) else color(MUTED))
                textSize = if (isHeading) 16f else 15f
                typeface = if (isHeading) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(0, if (line.isBlank()) dp(4) else dp(3), 0, dp(3))
            })
        }
        linkButtons.forEach { (label, url) ->
            body.addView(button(label) { openExternalUrl(url) })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
            val header = button(title) {
                body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            addView(header)
            addView(body)
        }
    }

    private fun manualFallbackSection(): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(6))
        }
        manualFallbackBody = body
        numberedSteps(
            listOf(
                "Open the Meta AI app.",
                "Go to your Meta Ray-Ban Display glasses settings.",
                "Open App connections.",
                "Choose Web Apps.",
                "Tap Add a Web App.",
                "Name: GlassCast",
                "URL: $RECEIVER_URL"
            )
        ).forEach { body.addView(it) }
        body.addView(button("Copy Receiver URL") { copyReceiverUrl() })
        body.addView(button("Open Receiver URL") { openExternalUrl(RECEIVER_URL) })
        body.addView(button("View Meta Setup Docs") { openExternalUrl(META_SETUP_DOCS_URL) })
        body.addView(button("View Meta Testing Docs") { openExternalUrl(META_TEST_DOCS_URL) })

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
            val header = button("Add manually instead") {
                body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            addView(header)
            addView(body)
        }
    }

    private fun localVideoDebugSection(): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(2))
        }
        localVideoDebugText = debugText("No local video selected.")
        body.addView(localVideoDebugText)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val header = button("Debug details") {
                body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            addView(header)
            addView(body)
        }
    }

    private fun aboutSettingsSection(): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(8), dp(14), dp(14))
            background = roundedDrawable(SURFACE, radiusDp = 14, strokeColor = NIGHT_BLUE, strokeDp = 1)

            addView(TextView(this@MainActivity).apply {
                text = "GlassCast"
                setTextColor(color(TEXT))
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(4), 0, dp(6))
            })
            addView(setupBody(APP_DESCRIPTION))
            addView(setupBody("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
            addView(setupBody("Receiver: $RECEIVER_URL"))
            addView(setupBody("Support: $SUPPORT_EMAIL"))
            addView(button("Open receiver") { openExternalUrl(RECEIVER_URL) })
            addView(button("Website") { openExternalUrl(WEBSITE_URL) })
            addView(button("Privacy Policy") { openExternalUrl(PRIVACY_POLICY_URL) })
            addView(button("Terms of Service") { openExternalUrl(TERMS_OF_SERVICE_URL) })
            addView(button("Email support") { openSupportEmail() })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, 0)
            addView(button("About & settings") {
                body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            })
            addView(body)
        }
    }

    private fun debugDetailsSection(): LinearLayout {
        debugDetailsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = roundedDrawable(SURFACE, radiusDp = 14, strokeColor = NIGHT_BLUE, strokeDp = 1)
        }

        lastSentUrlText = debugText("Last sent URL: None")
        lastCommandText = debugText("Last command: None")
        lastResponseText = debugText("Last API response: None")
        debugDetailsContainer.addView(lastSentUrlText)
        debugDetailsContainer.addView(lastCommandText)
        debugDetailsContainer.addView(lastResponseText)

        debugToggleText = TextView(this).apply {
            text = "Show debug details"
            setTextColor(color(MUTED))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener { toggleDebugDetails() }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(debugToggleText)
            addView(debugDetailsContainer)
        }
    }

    private fun toggleDebugDetails() {
        val show = debugDetailsContainer.visibility != View.VISIBLE
        debugDetailsContainer.visibility = if (show) View.VISIBLE else View.GONE
        debugToggleText.text = if (show) "Hide debug details" else "Show debug details"
    }

    private fun copyReceiverUrl() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GlassCast receiver URL", RECEIVER_URL))
        setupCopyStatusText.text = "Receiver URL copied."
    }

    private fun copyLocalHealthUrl() {
        val url = localVideoHealthUrl
        if (url.isNullOrBlank()) {
            showStatus("Start local video first.", isError = true)
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GlassCast health URL", url))
        showStatus("Health URL copied.")
    }

    private fun openLocalHealthUrl() {
        val url = localVideoHealthUrl
        if (url.isNullOrBlank()) {
            showStatus("Start local video first.", isError = true)
            return
        }
        openExternalUrl(url)
    }

    private fun copyLocalVideoUrl() {
        val url = localVideoUrl
        if (url.isNullOrBlank()) {
            showStatus("Start local video first.", isError = true)
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GlassCast video URL", url))
        showStatus("Video URL copied.")
    }

    private fun openLocalVideoUrl() {
        val url = localVideoUrl
        if (url.isNullOrBlank()) {
            showStatus("Start local video first.", isError = true)
            return
        }
        openExternalUrl(url)
    }

    private fun stopLocalVideoServing() {
        localVideoServer.stop()
        LocalVideoKeepAliveService.stop(this)
        localVideoUrl = null
        localVideoHealthUrl = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateLocalVideoStatus(
            mainStatus = "Local video server stopped.\nIf the receiver cannot play local video, make sure your phone and glasses are on the same Wi-Fi network.",
            servingUrl = null,
            healthUrl = null,
            castingUrl = null
        )
        showStatus("Local video server stopped.")
    }

    private fun metaAiFallbackActions(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(6))
            addView(button("Open Meta AI in Play Store") { openMetaAiInPlayStore() })
            addView(button("Try browser link") { openMetaWebAppDeepLinkInBrowser() })
            addView(button("Copy Receiver URL") { copyReceiverUrl() })
            addView(button("Add manually instead") {
                manualFallbackBody.visibility = View.VISIBLE
                manualFallbackBody.requestFocus()
            })
        }

    private fun openMetaAiAddWebAppFlow(context: Context) {
        val deepLinkUri = buildMetaWebAppDeepLinkUri()
        SafeLog.d(TAG, "Launching Meta AI add flow: $deepLinkUri")
        val intent = Intent(Intent.ACTION_VIEW, deepLinkUri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage(META_AI_PACKAGE)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        runCatching {
            context.startActivity(intent)
            setupFallbackActionsContainer.visibility = View.GONE
            setupCopyStatusText.text = "Opening Meta AI. Developer Mode is still required to add GlassCast."
        }.onFailure {
            SafeLog.d(TAG, "Meta AI launch failed", it)
            setupCopyStatusText.text = "Could not open Meta AI. Make sure the Meta AI app is installed and updated."
            setupFallbackActionsContainer.visibility = View.VISIBLE
        }
    }

    private fun buildMetaWebAppDeepLinkUri(): Uri {
        val uri = Uri.parse(META_WEBAPP_DEEP_LINK_BASE)
            .buildUpon()
            .appendQueryParameter("appName", GLASSCAST_APP_NAME)
            .appendQueryParameter("appUrl", GLASSCAST_RECEIVER_URL)
            .build()
        SafeLog.d(TAG, "Built Meta web app deep link: $uri")
        return uri
    }

    private fun openMetaAiInPlayStore() {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$META_AI_PACKAGE")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        runCatching { startActivity(marketIntent) }
            .onFailure { openExternalUrl("https://play.google.com/store/apps/details?id=$META_AI_PACKAGE") }
    }

    private fun openMetaWebAppDeepLinkInBrowser() {
        val uri = buildMetaWebAppDeepLinkUri()
        SafeLog.d(TAG, "Opening fallback browser deep link: $uri")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        runCatching { startActivity(intent) }
            .onFailure { showStatus("No browser found", isError = true) }
    }

    private fun openExternalUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") {
            showStatus("Could not open link", isError = true)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        runCatching { startActivity(intent) }
            .onFailure { showStatus("No browser found", isError = true) }
    }

    private fun openSupportEmail() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:$SUPPORT_EMAIL"))
        runCatching { startActivity(intent) }
            .onFailure { showStatus("No email app found", isError = true) }
    }

    private fun saveSessionCode() {
        val code = sessionCodeOrNull()
        if (code == null) {
            showStatus("Enter the session code from your glasses.", isError = true)
            return
        }

        showStatus("Paired with session $code", isSuccess = true)
        readyCard.text = "Ready to cast"
        readyCard.visibility = View.VISIBLE
        readySummary.text = "Session saved. Open GlassCast on your glasses."
        restartPlaybackPolling()
    }

    private fun castVideo() {
        clearInputFocusAndHideKeyboard()
        val code = sessionCodeOrNull()
            ?: return showStatus(MISSING_SESSION_MESSAGE, isError = true)
        val rawInput = urlInput.text.toString()
        val sanitizedUrl = sanitizeVideoUrl(rawInput)
        SafeLog.d(TAG, "Video link normalization: ${if (sanitizedUrl.isBlank()) "rejected" else "accepted"}")
        if (isLocalFileUrl(sanitizedUrl)) {
            showUnsupportedShareType()
            return
        }
        if (localVideoUri != null && localVideoUrl != null && sanitizedUrl != localVideoUrl && isLocalFileUrl(videoUrl)) {
            showUnsupportedShareType()
            return
        }
        videoUrl = sanitizedUrl
        setVideoUrl(sanitizedUrl)
        if (rawInput.isBlank()) return showStatus(MISSING_URL_MESSAGE, isError = true)
        if (sanitizedUrl.isBlank()) return showStatus(UNSUPPORTED_LINK_MESSAGE, isError = true)
        updateLastSentUrl(sanitizedUrl)
        updateLastCommand("Cast video")

        postJson(
            JSONObject()
                .put("code", code)
                .put("type", "cast")
                .put("url", sanitizedUrl),
            successMessage = "Cast sent"
        )
    }

    private fun castLocalVideoOrPick() {
        if (!ENABLE_LOCAL_VIDEO_EXPERIMENT) {
            showUnsupportedShareType()
            return
        }
        clearInputFocusAndHideKeyboard()
        if (localVideoUri == null) {
            openLocalVideoPicker()
            return
        }
        castLocalVideo()
    }

    private fun openLocalVideoPicker() {
        if (!ENABLE_LOCAL_VIDEO_EXPERIMENT) {
            showUnsupportedShareType()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, PICK_LOCAL_VIDEO_REQUEST) }
            .onFailure {
                SafeLog.e(TAG, "Could not open video picker", it)
                showStatus("Could not open video picker", isError = true)
            }
    }

    private fun prepareLocalVideo(uri: Uri, castAfterReady: Boolean, openedFromLocalShare: Boolean) {
        if (!ENABLE_LOCAL_VIDEO_EXPERIMENT) {
            showUnsupportedShareType()
            return
        }
        try {
            localVideoUri = uri
            openedFromShare = openedFromLocalShare
            primaryButton.text = "Cast Video"
            localVideoButton.text = "Cast Local Video"
            localVideoUrl = null
            localVideoHealthUrl = null
            localVideoName = displayName(uri) ?: "Local video"
            localVideoLength = -1L

            serveLocalVideo(uri) ?: return

            if (castAfterReady) {
                castLocalVideo()
            }
        } catch (e: Exception) {
            SafeLog.e(TAG, "Could not prepare local video", e)
            showStatus("Could not prepare local video", isError = true)
        }
    }

    private fun serveLocalVideo(uri: Uri): String? {
        if (!ENABLE_LOCAL_VIDEO_EXPERIMENT) {
            showUnsupportedShareType()
            return null
        }
        val result = runCatching { localVideoServer.serve(uri) }
            .getOrElse {
                SafeLog.e(TAG, "Could not start local video stream", it)
                localVideoUrl = null
                localVideoHealthUrl = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                localVideoStatusText.text = "Could not start local video stream. Make sure your phone and glasses are on the same network."
                showStatus("Could not start local video stream. Make sure your phone and glasses are on the same network.", isError = true)
                return null
            }

        localVideoUrl = result.url
        localVideoHealthUrl = result.healthUrl
        localVideoName = result.displayName
        localVideoLength = result.contentLength
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        LocalVideoKeepAliveService.start(this)
        updateLocalVideoStatus(
            mainStatus = "Open Video URL in Chrome before testing glasses.",
            servingUrl = result.url,
            healthUrl = result.healthUrl,
            castingUrl = null
        )
        showStatus("Ready to cast local video.")
        updateSessionSummary()
        return result.url
    }

    private fun castLocalVideo() {
        if (!ENABLE_LOCAL_VIDEO_EXPERIMENT) {
            showUnsupportedShareType()
            return
        }
        try {
            clearInputFocusAndHideKeyboard()
            val uri = localVideoUri
            if (uri == null) {
                openLocalVideoPicker()
                return
            }

            val serverUrl = localVideoUrl ?: run {
                serveLocalVideo(uri)
            } ?: return

            if (isLocalFileUrl(serverUrl)) {
                showUnsupportedShareType()
                return
            }

            val code = sessionCodeOrNull() ?: return showStatus(MISSING_SESSION_MESSAGE, isError = true)
            videoUrl = serverUrl
            updateLastSentUrl(serverUrl)
            updateLastCommand("Cast video")
            updateLocalVideoStatus(
                mainStatus = "Cast sent.\nKeep phone and glasses on the same Wi-Fi.",
                servingUrl = serverUrl,
                healthUrl = localVideoHealthUrl,
                castingUrl = serverUrl
            )

            postJson(
                JSONObject()
                    .put("code", code)
                    .put("type", "cast")
                    .put("url", serverUrl),
                successMessage = "Cast sent"
            )
        } catch (e: Exception) {
            SafeLog.e(TAG, "Could not cast local video", e)
            showStatus("Could not cast local video", isError = true)
        }
    }

    private fun sendPlaybackCommand(command: String) {
        clearInputFocusAndHideKeyboard()
        val code = sessionCodeOrNull() ?: return showStatus(MISSING_SESSION_MESSAGE, isError = true)

        postJson(
            JSONObject()
                .put("code", code)
                .put("type", "command")
                .put("command", command),
            successMessage = "Playback command sent"
        )
        updateLastCommand(command)
    }

    private fun sendTimelineCommand(command: String) {
        if (!timelineSeekAvailable) {
            showStatus(TIMELINE_UNAVAILABLE_FOR_PLAYER, isError = false)
            return
        }
        sendPlaybackCommand(command)
    }

    private fun toggleCaptions() {
        if (!captionsAvailable || captionsEnabled == null || pendingCaptionsEnabled != null) return

        val previousState = captionsEnabled ?: return
        val requestedState = !previousState
        val command = if (requestedState) "captionsOn" else "captionsOff"
        pendingCaptionsEnabled = requestedState
        captionsPendingUntilMs = System.currentTimeMillis() + CAPTIONS_CONFIRM_TIMEOUT_MS
        pollHandler.removeCallbacks(captionsPendingTimeout)
        pollHandler.postDelayed(captionsPendingTimeout, CAPTIONS_CONFIRM_TIMEOUT_MS)
        renderCaptionState()

        sendPlaybackCommand(command, successMessage = "Caption change requested") {
            if (pendingCaptionsEnabled != requestedState) return@sendPlaybackCommand
            clearCaptionPendingState()
            captionsEnabled = previousState
            renderCaptionState()
        }
    }

    private fun sendPlaybackCommand(
        command: String,
        successMessage: String,
        onFailure: (() -> Unit)?
    ) {
        clearInputFocusAndHideKeyboard()
        val code = sessionCodeOrNull()
        if (code == null) {
            onFailure?.invoke()
            showStatus(MISSING_SESSION_MESSAGE, isError = true)
            return
        }

        postJson(
            JSONObject()
                .put("code", code)
                .put("type", "command")
                .put("command", command),
            successMessage = successMessage,
            onFailure = onFailure
        )
        updateLastCommand(command)
    }

    private fun sendSeekTo(seconds: Double) {
        clearInputFocusAndHideKeyboard()
        val code = sessionCodeOrNull()
        if (code == null) {
            isUserScrubbing = false
            return showStatus(MISSING_SESSION_MESSAGE, isError = true)
        }
        if (!timelineSeekAvailable || !isKnownDuration(timelineDurationSeconds)) {
            isUserScrubbing = false
            return showTimelineUnavailable(TIMELINE_UNAVAILABLE_FOR_PLAYER)
        }

        optimisticCurrentSeconds = clampTime(seconds, timelineDurationSeconds)
        ignorePlaybackPositionUntilMs = System.currentTimeMillis() + SEEK_POSITION_IGNORE_MS
        updateLastCommand("seekTo ${formatTime(seconds)}")
        postJson(
            JSONObject()
                .put("code", code)
                .put("type", "command")
                .put("command", "seekTo")
                .put("time", seconds),
            successMessage = "Playback command sent"
        ) {
            pollHandler.postDelayed({
                isUserScrubbing = false
                fetchPlaybackState()
            }, SCRUB_RELEASE_DELAY_MS)
        }
    }

    private fun postJson(payload: JSONObject, successMessage: String = "Sent") {
        postJson(payload, successMessage = successMessage, afterComplete = null)
    }

    private fun postJson(
        payload: JSONObject,
        successMessage: String = "Sent",
        onFailure: (() -> Unit)? = null,
        afterComplete: (() -> Unit)? = null
    ) {
        showStatus("Sending...")
        val jsonBody = payload.toString()
        SafeLog.d(TAG, "Sending receiver command: ${payload.optString("type", "unknown")}")
        val request = Request.Builder()
            .url(SESSION_ENDPOINT)
            .post(jsonBody.toRequestBody(jsonType))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val error = NETWORK_ERROR_MESSAGE
                runOnUiThread {
                    updateLastResponse(error)
                    showStatus(error, isError = true)
                    onFailure?.invoke()
                    afterComplete?.invoke()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    val responseText = if (body.isBlank()) "HTTP ${it.code}" else body
                    runOnUiThread {
                        updateLastResponse(responseText)
                        if (it.isSuccessful) {
                            showStatus(successMessage, isSuccess = successMessage == "Cast sent")
                        } else {
                            showStatus(apiError(body, it.code), isError = true)
                            onFailure?.invoke()
                        }
                        afterComplete?.invoke()
                    }
                }
            }
        })
    }

    private fun startPlaybackPolling() {
        if (normalizeSessionCode(codeInput.text.toString()).isBlank()) {
            showTimelineUnavailable(TIMELINE_UNAVAILABLE_FOR_PLAYER)
            return
        }
        if (isPolling) return
        isPolling = true
        pollHandler.post(pollRunnable)
    }

    private fun restartPlaybackPolling() {
        if (normalizeSessionCode(codeInput.text.toString()).isBlank()) {
            stopPlaybackPolling()
            showTimelineUnavailable(TIMELINE_UNAVAILABLE_FOR_PLAYER)
            return
        }
        if (!isPolling) {
            startPlaybackPolling()
            return
        }
        if (!isPolling) return
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
    }

    private fun stopPlaybackPolling() {
        isPolling = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun fetchPlaybackState() {
        val code = normalizeSessionCode(codeInput.text.toString())
        if (code.isBlank()) {
            runOnUiThread { showTimelineUnavailable(TIMELINE_UNAVAILABLE_FOR_PLAYER) }
            return
        }

        fetchPlaybackStateFrom("$STATE_ENDPOINT?code=${Uri.encode(code)}", fallbackToSessionEndpoint = true)
    }

    private fun fetchPlaybackStateFrom(url: String, fallbackToSessionEndpoint: Boolean) {
        val code = normalizeSessionCode(codeInput.text.toString())
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (fallbackToSessionEndpoint) {
                    fetchPlaybackStateFrom("$SESSION_ENDPOINT?code=${Uri.encode(code)}", fallbackToSessionEndpoint = false)
                } else {
                    runOnUiThread { showStatus(NETWORK_ERROR_MESSAGE, isError = true) }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        if (fallbackToSessionEndpoint) {
                            fetchPlaybackStateFrom("$SESSION_ENDPOINT?code=${Uri.encode(code)}", fallbackToSessionEndpoint = false)
                        } else {
                            runOnUiThread { showStatus(apiError(body, it.code), isError = true) }
                        }
                        return
                    }

                    val state = runCatching { parsePlaybackState(JSONObject(body)) }.getOrNull()
                    runOnUiThread {
                        if (state == null) {
                            showTimelineUnavailable(TIMELINE_UNAVAILABLE_FOR_PLAYER)
                        } else {
                            applyPlaybackState(state)
                        }
                    }
                }
            }
        })
    }

    private fun parsePlaybackState(json: JSONObject): PlaybackState {
        val stateJson = playbackStateJson(json)
        val current = findDouble(stateJson, "currentTime", "current", "time", "position", "playedSeconds") ?: 0.0
        val duration = findDouble(stateJson, "duration", "totalDuration", "length") ?: 0.0
        val title = sanitizeTitle(
            findString(stateJson, "title", "videoTitle", "nowPlaying", "name")
                ?: findString(json, "title", "videoTitle", "nowPlaying", "name")
        )
        val mode = sanitizePlainText(
            findString(stateJson, "mode")
                ?: findString(json, "mode")
                ?: findString(stateJson, "status", "playbackStatus", "state", "playerState")
                ?: findString(json, "status", "playbackStatus", "state", "playerState")
        )
        val canSeek = findBoolean(stateJson, "canSeek") ?: findBoolean(json, "canSeek")
        val timelineAvailable = findBoolean(stateJson, "timelineAvailable") ?: findBoolean(json, "timelineAvailable")
        val controlsLimited = findBoolean(stateJson, "controlsLimited") ?: findBoolean(json, "controlsLimited")
        val captionsAvailable = findBoolean(stateJson, "captionsAvailable", "hasCaptions", "subtitlesAvailable")
            ?: findBoolean(json, "captionsAvailable", "hasCaptions", "subtitlesAvailable")
        val captionsEnabled = findBoolean(stateJson, "captionsEnabled", "captionsOn", "subtitlesEnabled")
            ?: findBoolean(json, "captionsEnabled", "captionsOn", "subtitlesEnabled")
        val playing = findBoolean(stateJson, "playing", "isPlaying", "paused")
            ?.let { if (stateJson.has("paused")) !it else it }
            ?: mode.equals("playing", ignoreCase = true)
        val url = sanitizePlainText(
            findString(stateJson, "url", "videoUrl", "src")
                ?: findString(json, "url", "videoUrl", "src")
        )

        return PlaybackState(
            currentTime = if (current.isFinite()) current.coerceAtLeast(0.0) else 0.0,
            duration = duration,
            playing = playing,
            mode = mode.orEmpty(),
            canSeek = canSeek,
            timelineAvailable = timelineAvailable,
            controlsLimited = controlsLimited,
            captionsAvailable = captionsAvailable,
            captionsEnabled = captionsEnabled,
            title = title,
            url = url
        )
    }

    private fun applyPlaybackState(state: PlaybackState) {
        nowPlayingText.text = state.title?.takeIf { it.isNotBlank() } ?: "Now playing"
        applyCaptionState(state)

        if (!isTimelineSeekAvailable(state)) {
            val message = if (state.mode.equals("youtube", ignoreCase = true) && !isKnownDuration(state.duration)) {
                "Live / timeline unavailable"
            } else {
                TIMELINE_UNAVAILABLE_FOR_PLAYER
            }
            showTimelineUnavailable(message, durationLabel = if (state.playing) "LIVE" else "--:--")
            return
        }

        timelineSeekAvailable = true
        timelineDurationSeconds = state.duration
        timelineSeekBar.isEnabled = true
        setTimelineButtonsEnabled(true)
        timelineStatusText.text = ""
        durationText.text = formatTime(state.duration)

        if (!isUserScrubbing) {
            val current = if (System.currentTimeMillis() < ignorePlaybackPositionUntilMs) {
                optimisticCurrentSeconds ?: state.currentTime
            } else {
                optimisticCurrentSeconds = null
                state.currentTime
            }.coerceIn(0.0, state.duration)
            currentTimeText.text = formatTime(current)
            timelineSeekBar.progress = secondsToProgress(current)
        }
    }

    private fun applyCaptionState(state: PlaybackState) {
        captionsAvailable = state.captionsAvailable == true && state.captionsEnabled != null
        captionsEnabled = if (captionsAvailable) state.captionsEnabled else null

        val pendingState = pendingCaptionsEnabled
        if (pendingState != null && !captionsAvailable) {
            clearCaptionPendingState()
        } else if (pendingState != null && captionsEnabled == pendingState) {
            clearCaptionPendingState()
        } else if (pendingState != null && System.currentTimeMillis() >= captionsPendingUntilMs) {
            clearCaptionPendingState()
        }
        renderCaptionState()
    }

    private fun clearCaptionPendingState() {
        pendingCaptionsEnabled = null
        captionsPendingUntilMs = 0L
        pollHandler.removeCallbacks(captionsPendingTimeout)
    }

    private fun renderCaptionState() {
        if (!::captionsButton.isInitialized || !::captionsStatusText.isInitialized) return
        val pendingState = pendingCaptionsEnabled
        captionsButton.isEnabled = captionsAvailable && captionsEnabled != null && pendingState == null
        captionsButton.alpha = when {
            !captionsAvailable -> 0.45f
            pendingState != null -> 0.7f
            else -> 1f
        }
        captionsButton.text = when {
            pendingState == true -> "Turning captions on…"
            pendingState == false -> "Turning captions off…"
            captionsEnabled == true -> CAPTIONS_ON
            captionsEnabled == false -> CAPTIONS_OFF
            else -> "Captions"
        }
        captionsStatusText.text = when {
            !captionsAvailable -> CAPTIONS_NOT_AVAILABLE
            pendingState != null -> "Waiting for receiver confirmation…"
            captionsEnabled == true -> CAPTIONS_ON
            else -> CAPTIONS_OFF
        }
    }

    private fun isTimelineSeekAvailable(state: PlaybackState): Boolean =
        !state.mode.equals("dailymotion", ignoreCase = true) &&
            isKnownDuration(state.duration) &&
            state.canSeek != false &&
            state.timelineAvailable != false

    private fun showTimelineUnavailable(message: String, durationLabel: String = "--:--") {
        timelineSeekAvailable = false
        timelineDurationSeconds = 0.0
        optimisticCurrentSeconds = null
        ignorePlaybackPositionUntilMs = 0L
        if (!isUserScrubbing) {
            timelineSeekBar.progress = 0
            currentTimeText.text = "0:00"
        }
        isUserScrubbing = false
        timelineSeekBar.isEnabled = false
        setTimelineButtonsEnabled(false)
        durationText.text = durationLabel
        timelineStatusText.text = message
    }

    private fun setTimelineButtonsEnabled(enabled: Boolean) {
        if (!::seekBackButton.isInitialized || !::seekForwardButton.isInitialized) return
        listOf(seekBackButton, seekForwardButton).forEach { button ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun progressToSeconds(progress: Int): Double {
        if (!isKnownDuration(timelineDurationSeconds)) return 0.0
        return (progress.toDouble() / SEEK_BAR_MAX.toDouble()) * timelineDurationSeconds
    }

    private fun secondsToProgress(seconds: Double): Int {
        if (!isKnownDuration(timelineDurationSeconds)) return 0
        return ((seconds / timelineDurationSeconds) * SEEK_BAR_MAX).toInt().coerceIn(0, SEEK_BAR_MAX)
    }

    private fun clampTime(seconds: Double, duration: Double): Double {
        if (!seconds.isFinite()) return 0.0
        val nonNegative = seconds.coerceAtLeast(0.0)
        return if (isKnownDuration(duration)) nonNegative.coerceAtMost(duration) else nonNegative
    }

    private fun isKnownDuration(duration: Double): Boolean = duration.isFinite() && duration > 0.0

    private fun formatTime(seconds: Double): String {
        val totalSeconds = seconds.toInt().coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val remainingSeconds = totalSeconds % 60

        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
        } else {
            "%d:%02d".format(minutes, remainingSeconds)
        }
    }

    private fun findDouble(json: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            when (val value = json.opt(key)) {
                is Number -> return value.toDouble()
                is String -> value.toDoubleOrNull()?.let { return it }
            }
        }

        val iterator = json.keys()
        while (iterator.hasNext()) {
            val child = json.opt(iterator.next())
            if (child is JSONObject) {
                findDouble(child, *keys)?.let { return it }
            }
        }

        return null
    }

    private fun findString(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val value = json.opt(key)
            val text = when (value) {
                is String -> value
                is Number, is Boolean -> value.toString()
                else -> null
            }?.trim()
            if (!text.isNullOrBlank()) return text
        }

        val iterator = json.keys()
        while (iterator.hasNext()) {
            val child = json.opt(iterator.next())
            if (child is JSONObject) {
                findString(child, *keys)?.let { return it }
            }
        }

        return null
    }

    private fun findBoolean(json: JSONObject, vararg keys: String): Boolean? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            when (val value = json.opt(key)) {
                is Boolean -> return value
                is String -> value.toBooleanStrictOrNull()?.let { return it }
            }
        }

        val iterator = json.keys()
        while (iterator.hasNext()) {
            val child = json.opt(iterator.next())
            if (child is JSONObject) {
                findBoolean(child, *keys)?.let { return it }
            }
        }

        return null
    }

    private fun playbackStateJson(json: JSONObject): JSONObject {
        val directKeys = listOf("playbackState", "playback", "player", "state", "session")
        for (key in directKeys) {
            val child = json.opt(key)
            if (child is JSONObject && hasPlaybackFields(child)) return child
        }
        return json
    }

    private fun hasPlaybackFields(json: JSONObject): Boolean =
        findDouble(json, "currentTime", "current", "time", "position", "playedSeconds") != null ||
            findDouble(json, "duration", "totalDuration", "length") != null ||
            findString(json, "mode") != null ||
            findBoolean(json, "canSeek", "timelineAvailable", "controlsLimited") != null

    private fun sanitizeTitle(value: String?): String? {
        val text = sanitizePlainText(value) ?: return null
        if (text.startsWith("{")) {
            val parsedTitle = runCatching {
                sanitizeTitle(findString(JSONObject(text), "title", "videoTitle", "nowPlaying", "name"))
            }.getOrNull()
            if (!parsedTitle.isNullOrBlank()) return parsedTitle
        }

        return text
            .substringBefore(" - {")
            .substringBefore(" {")
            .trim()
            .takeIf { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("[") }
    }

    private fun sanitizePlainText(value: String?): String? {
        val text = value?.trim().orEmpty()
        return text.takeIf { it.isNotBlank() && !looksLikeJson(it) }
    }

    private fun looksLikeJson(value: String): Boolean =
        (value.startsWith("{") && value.endsWith("}")) || (value.startsWith("[") && value.endsWith("]"))

    private fun apiError(body: String, statusCode: Int): String {
        SafeLog.d(TAG, "Receiver rejected request: HTTP $statusCode; response present=${body.isNotBlank()}")
        val normalizedBody = body.lowercase()
        val describesExpiredSession = "session" in normalizedBody &&
            ("expired" in normalizedBody || "not found" in normalizedBody || "invalid" in normalizedBody)
        if (describesExpiredSession) return SESSION_EXPIRED_MESSAGE
        return when (statusCode) {
            404, 410 -> SESSION_EXPIRED_MESSAGE
            400, 422 -> "The receiver could not use this link. $PLAYBACK_BLOCKED_MESSAGE"
            401, 403 -> "The receiver rejected this request. Check the session code and try again."
            else -> "The receiver returned an error (HTTP $statusCode). Try again."
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> handleSendIntent(intent)
            Intent.ACTION_VIEW -> handleViewIntent(intent)
        }
    }

    private fun handleSendIntent(intent: Intent) {
        val type = intent.type.orEmpty()
        val sharedStreamUri = streamUri(intent)
        if (type.equals("text/plain", ignoreCase = true)) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                ?: ""
            val url = sanitizeVideoUrl(sharedText)
            SafeLog.d(TAG, "Shared video link normalization: ${if (url.isBlank()) "rejected" else "accepted"}")
            if (url.isBlank()) {
                showStatus(if (sharedText.isBlank()) MISSING_URL_MESSAGE else UNSUPPORTED_LINK_MESSAGE, isError = true)
            } else if (isLocalFileUrl(url)) {
                if (!ENABLE_LOCAL_VIDEO_EXPERIMENT) {
                    showUnsupportedShareType()
                    return
                }
                val uri = Uri.parse(url)
                if (isContentUri(uri)) {
                    takeReadPermission(uri, intent.flags)
                    prepareLocalVideo(uri, castAfterReady = false, openedFromLocalShare = true)
                } else {
                    showUnsupportedShareType()
                }
            } else {
                useSharedUrl(url)
            }
            return
        }

        if (type.startsWith("video/", ignoreCase = true) || isContentUri(sharedStreamUri)) {
            if (!ENABLE_LOCAL_VIDEO_EXPERIMENT) {
                showUnsupportedShareType()
                return
            }
            val uri = sharedStreamUri
            if (uri == null) {
                showUnsupportedShareType()
                return
            }
            takeReadPermission(uri, intent.flags)
            prepareLocalVideo(uri, castAfterReady = false, openedFromLocalShare = true)
            return
        }
    }

    private fun handleViewIntent(intent: Intent) {
        val uri = intent.data ?: return showStatus(MISSING_URL_MESSAGE, isError = true)
        if (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            val rawInput = uri.toString()
            val url = sanitizeVideoUrl(rawInput)
            SafeLog.d(TAG, "Opened video link normalization: ${if (url.isBlank()) "rejected" else "accepted"}")
            if (url.isBlank()) {
                showStatus(UNSUPPORTED_LINK_MESSAGE, isError = true)
            } else {
                useSharedUrl(url)
            }
        } else {
            showStatus(UNSUPPORTED_LINK_MESSAGE, isError = true)
        }
    }

    private fun useSharedUrl(url: String) {
        if (isLocalFileUrl(url)) {
            showLocalFileMustBeServed()
            return
        }
        openedFromShare = true
        setVideoUrl(url)
        clearInputFocusAndHideKeyboard()
        primaryButton.text = "Cast Shared Video"
        updateSessionSummary()

        val code = normalizeSessionCode(codeInput.text.toString())
        if (code.isBlank()) {
            showStatus(MISSING_SESSION_MESSAGE, isError = true)
            return
        }

        setSessionCode(code)
        showStatus("Ready to cast")
    }

    private fun sanitizeVideoUrl(input: String): String = SupportedLinkNormalizer.normalize(input).orEmpty()

    private fun sessionCodeOrNull(): String? {
        val code = normalizeSessionCode(codeInput.text.toString())
        if (code.isNotBlank()) {
            prefs.edit().putString(KEY_SESSION_CODE, code).apply()
            setSessionCode(code)
        }
        updateSessionSummary()
        return code.ifBlank { null }
    }

    private fun setSessionCode(code: String) {
        suppressCodeWatcher = true
        codeInput.setText(normalizeSessionCode(code))
        codeInput.setSelection(codeInput.text.length)
        suppressCodeWatcher = false
    }

    private fun setVideoUrl(url: String) {
        videoUrl = sanitizeVideoUrl(url)
        suppressUrlWatcher = true
        urlInput.setText(videoUrl)
        urlInput.setSelection(urlInput.text.length)
        suppressUrlWatcher = false
    }

    private fun updateSessionSummary() {
        val code = normalizeSessionCode(codeInput.text.toString())
        if (code.isBlank()) {
            sessionSummary.text = "No saved session"
            readySummary.text = "Open the receiver on your glasses, then enter its session code."
            readyCard.visibility = View.GONE
        } else {
            sessionSummary.text = "Paired with session $code"
            readyCard.text = "Ready to cast"
            readyCard.visibility = View.VISIBLE
            readySummary.text = if (openedFromShare && videoUrl.isNotBlank()) {
                "Session saved. Ready to cast shared video."
            } else {
                "Session saved."
            }
        }
    }

    private fun normalizeSessionCode(value: String?): String = value.orEmpty().trim().uppercase()

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun isContentUri(uri: Uri?): Boolean = uri?.scheme.equals("content", ignoreCase = true)

    private fun isLocalFileUrl(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme }.getOrNull()
        return scheme.equals("content", ignoreCase = true) || scheme.equals("file", ignoreCase = true)
    }

    private fun showLocalFileMustBeServed() {
        showUnsupportedShareType()
    }

    private fun showUnsupportedShareType() {
        showStatus(UNSUPPORTED_LINK_MESSAGE, isError = true)
    }

    private fun updateLocalVideoStatus(
        mainStatus: String,
        servingUrl: String?,
        healthUrl: String?,
        castingUrl: String?
    ) {
        val lengthWarning = if (localVideoLength < 0) {
            "\nVideo length is unknown, so seeking may be limited."
        } else {
            ""
        }
        val healthSummary = if (BuildConfig.DEBUG) healthUrl?.let { "\nHealth URL: $it" }.orEmpty() else ""
        val videoSummary = if (BuildConfig.DEBUG) servingUrl?.let { "\nVideo URL: $it" }.orEmpty() else ""
        localVideoStatusText.text = "$mainStatus\nIf the receiver cannot play local video, make sure your phone and glasses are on the same Wi-Fi network.$healthSummary$videoSummary"
        if (BuildConfig.DEBUG && ::localVideoDebugText.isInitialized) localVideoDebugText.text = buildString {
            append("Selected local video: ")
            append(if (localVideoName.isBlank()) "None" else localVideoName)
            append('\n')
            append("Health URL: ")
            append(healthUrl ?: "Not serving yet")
            append('\n')
            append("Video URL: ")
            append(servingUrl ?: "Not serving yet")
            append('\n')
            append("Casting local video URL: ")
            append(castingUrl ?: "Not cast yet")
            append(lengthWarning)
        }
    }

    private fun takeReadPermission(uri: Uri, flags: Int) {
        val readFlag = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlag == 0) return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun displayName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?: return uri.lastPathSegment?.substringAfterLast('/')
        cursor.use {
            if (!it.moveToFirst()) return uri.lastPathSegment?.substringAfterLast('/')
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0 || it.isNull(index)) return uri.lastPathSegment?.substringAfterLast('/')
            return it.getString(index)
        }
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(MUTED))
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(22), 0, dp(6))
    }

    private fun helper(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(MUTED))
        textSize = 14f
        setPadding(0, 0, 0, dp(8))
    }

    private fun debugText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(MUTED))
        textSize = 13f
        setPadding(0, dp(8), 0, 0)
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(MUTED))
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(22), 0, dp(6))
    }

    private fun input(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setTextColor(color(TEXT))
        setHintTextColor(color(MUTED))
        textSize = 18f
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = roundedDrawable(SURFACE, radiusDp = 14, strokeColor = NIGHT_BLUE, strokeDp = 1)
    }

    private fun button(text: String, large: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = if (large) 20f else 17f
            isAllCaps = false
            minHeight = if (large) dp(64) else dp(54)
            elevation = dp(if (large) 4 else 2).toFloat()
            setTextColor(color(if (large) BG else TEXT))
            background = roundedDrawable(
                fillColor = if (large) PRIMARY else SECONDARY,
                radiusDp = if (large) 18 else 14,
                strokeColor = if (large) MINT else NIGHT_BLUE,
                strokeDp = 1
            )
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                clearInputFocusAndHideKeyboard()
                onClick()
            }
            applyButtonPressFeedback(this, large)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (large) dp(64) else dp(54)
            ).apply {
                topMargin = if (large) dp(22) else dp(10)
            }
        }

    private fun applyButtonPressFeedback(button: Button, large: Boolean) {
        val normalElevation = dp(if (large) 4 else 2).toFloat()
        val pressedElevation = dp(1).toFloat()
        button.setOnTouchListener { view, event ->
            if (!view.isEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate()
                        .scaleX(0.985f)
                        .scaleY(0.985f)
                        .translationY(dp(2).toFloat())
                        .alpha(0.92f)
                        .setDuration(BUTTON_PRESS_ANIM_MS)
                        .start()
                    view.elevation = pressedElevation
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(BUTTON_RELEASE_ANIM_MS)
                        .start()
                    view.elevation = normalElevation
                }
            }
            false
        }
    }

    private fun roundedDrawable(
        fillColor: String,
        radiusDp: Int,
        strokeColor: String? = null,
        strokeDp: Int = 0
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color(fillColor))
            if (strokeColor != null && strokeDp > 0) {
                setStroke(dp(strokeDp), color(strokeColor))
            }
        }

    private fun clearInputFocusAndHideKeyboard() {
        val focusedView = currentFocus
        codeInput.clearFocus()
        urlInput.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow((focusedView ?: window.decorView).windowToken, 0)
    }

    private fun showStatus(message: String, isError: Boolean = false, isSuccess: Boolean = false) {
        statusText.text = message
        statusText.setTextColor(
            color(
                when {
                    isError -> DANGER
                    isSuccess -> SUCCESS
                    else -> MUTED
                }
            )
        )
    }

    private fun updateLastSentUrl(url: String) {
        lastSentUrl = url
        if (::lastSentUrlText.isInitialized) {
            lastSentUrlText.text = "Last sent URL: $lastSentUrl"
        }
    }

    private fun updateLastCommand(command: String) {
        if (::lastCommandText.isInitialized) {
            lastCommandText.text = "Last command: $command"
        }
    }

    private fun updateLastResponse(response: String) {
        lastResponse = response
        if (::lastResponseText.isInitialized) {
            lastResponseText.text = "Last API response: $lastResponse"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun color(hex: String): Int = Color.parseColor(hex)

    abstract class SimpleTextWatcher : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
    }

    data class PlaybackState(
        val currentTime: Double,
        val duration: Double,
        val playing: Boolean,
        val mode: String,
        val canSeek: Boolean?,
        val timelineAvailable: Boolean?,
        val controlsLimited: Boolean?,
        val captionsAvailable: Boolean?,
        val captionsEnabled: Boolean?,
        val title: String?,
        val url: String?
    )

    companion object {
        private const val API_BASE_URL = "https://glasscast.znichka.xyz"
        private const val META_AI_PACKAGE = "com.facebook.stella"
        private const val GLASSCAST_APP_NAME = "GlassCast"
        private const val GLASSCAST_RECEIVER_URL = "https://glasscast.znichka.xyz/"
        private const val RECEIVER_URL = GLASSCAST_RECEIVER_URL
        private const val WEBSITE_URL = "https://www.znichka.xyz/"
        private const val PRIVACY_POLICY_URL = "https://www.znichka.xyz/privacy-policy"
        private const val TERMS_OF_SERVICE_URL = "https://www.znichka.xyz/terms-of-service"
        private const val SUPPORT_EMAIL = "hello@znichka.xyz"
        private const val APP_DESCRIPTION = "Send supported video links from your phone to a receiver designed for smart-glasses displays."
        private const val SESSION_ENDPOINT = "$API_BASE_URL/api/session"
        private const val STATE_ENDPOINT = "$API_BASE_URL/api/session/state"
        private const val META_WEBAPP_DEEP_LINK_BASE = "https://facebook.com/fb_viewapp/web_app_deep_link"
        private const val META_SETUP_DOCS_URL = "https://wearables.developer.meta.com/docs/develop/webapps/setup/"
        private const val META_TEST_DOCS_URL = "https://wearables.developer.meta.com/docs/develop/webapps/test"
        private val TROUBLESHOOTING_TITLES = setOf(
            "I do not see Developer Mode",
            "Add button does not open Meta AI",
            "I do not see Web Apps",
            "The receiver URL will not open",
            "The session code does not work",
            "YouTube video does not play",
            "Live video timeline is unavailable"
        )
        private const val PREFS_NAME = "glasscast"
        private const val KEY_SESSION_CODE = "session_code"
        private const val PLAYBACK_POLL_MS = 1_000L
        private const val SCRUB_RELEASE_DELAY_MS = 500L
        private const val SEEK_POSITION_IGNORE_MS = 800L
        private const val CAPTIONS_CONFIRM_TIMEOUT_MS = 5_000L
        private const val SEEK_BAR_MAX = 1_000
        private const val TIMELINE_UNAVAILABLE_FOR_PLAYER = "Timeline controls are unavailable for this video."
        private const val MISSING_SESSION_MESSAGE = "Enter the session code shown by the receiver."
        private const val MISSING_URL_MESSAGE = "Paste or share a supported video link."
        private const val UNSUPPORTED_LINK_MESSAGE = "This link type isn't supported. Use an http or https video link."
        private const val PLAYBACK_BLOCKED_MESSAGE = "The video service may block playback or timeline controls."
        private const val SESSION_EXPIRED_MESSAGE = "The receiver session expired. Open the receiver and enter its new session code."
        private const val NETWORK_ERROR_MESSAGE = "Could not reach the receiver. Check your connection and try again."
        private const val CAPTIONS_ON = "Captions On"
        private const val CAPTIONS_OFF = "Captions Off"
        private const val CAPTIONS_NOT_AVAILABLE = "Captions unavailable for this video"
        private const val PICK_LOCAL_VIDEO_REQUEST = 2001
        private const val TAG = "GlassCast"
        private const val BASE_CONTENT_TOP_PADDING = 36
        private const val HEADER_BREATHING_ROOM = 28
        private const val BUTTON_PRESS_ANIM_MS = 70L
        private const val BUTTON_RELEASE_ANIM_MS = 95L

        private const val BRAND_LOGO_ASSET = "brand/glasscast-logo-primary-transparent.png"

        private const val BG = "#020516"
        private const val SURFACE = "#0C1326"
        private const val SURFACE_ALT = "#172653"
        private const val NIGHT_BLUE = "#172653"
        private const val TEXT = "#E9EEF9"
        private const val MUTED = "#8E9BC2"
        private const val PRIMARY = "#32D17C"
        private const val SECONDARY = "#5A4FCF"
        private const val MINT = "#7EF0B2"
        private const val STAR_GOLD = "#F6D35C"
        private const val SUCCESS = "#32D17C"
        private const val DANGER = "#FF8A8A"

    }
}
