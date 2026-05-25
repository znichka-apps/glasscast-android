package com.glasscast.sender

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
    private lateinit var autoCastCheck: CheckBox
    private lateinit var sessionSummary: TextView
    private lateinit var readySummary: TextView
    private lateinit var nowPlayingText: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var durationText: TextView
    private lateinit var timelineSeekBar: SeekBar
    private lateinit var timelineStatusText: TextView
    private lateinit var statusText: TextView
    private lateinit var lastSentUrlText: TextView
    private lateinit var lastResponseText: TextView
    private lateinit var setupGuideContainer: LinearLayout
    private lateinit var setupCopyStatusText: TextView
    private lateinit var localVideoButton: Button
    private lateinit var localVideoStatusText: TextView

    private var videoUrl = ""
    private var lastSentUrl = ""
    private var lastResponse = ""
    private var localVideoUri: Uri? = null
    private var localVideoUrl: String? = null
    private var localVideoName = ""
    private var localVideoLength = -1L
    private var openedFromShare = false
    private var suppressCodeWatcher = false
    private var suppressUrlWatcher = false
    private var isPolling = false
    private var isUserScrubbing = false
    private var timelineDurationSeconds = 0.0
    private var localScrubSeconds = 0.0
    private var ignorePlaybackPositionUntilMs = 0L
    private var optimisticCurrentSeconds: Double? = null

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
        autoCastCheck.isChecked = prefs.getBoolean(KEY_AUTO_CAST, false)
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
            localVideoServer.stop()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API, retained here to avoid adding activity-result dependencies.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_LOCAL_VIDEO_REQUEST || resultCode != RESULT_OK) return
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
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        root.addView(content)

        content.addView(TextView(this).apply {
            text = "GlassCast"
            setTextColor(Color.WHITE)
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
        })

        content.addView(TextView(this).apply {
            text = "by Znichka"
            setTextColor(color(MUTED))
            textSize = 16f
            setPadding(0, dp(2), 0, dp(6))
        })

        content.addView(setupEntryCard())
        setupGuideContainer = setupGuide().apply {
            visibility = View.GONE
        }
        content.addView(setupGuideContainer)

        content.addView(sectionTitle("Saved session"))
        sessionSummary = TextView(this).apply {
            setTextColor(Color.WHITE)
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
        content.addView(button("Save or Pair") {
            sessionCodeOrNull()
                ?.let { showStatus("Ready") }
                ?: showStatus("Missing session code", isError = true)
        })

        autoCastCheck = CheckBox(this).apply {
            text = "Auto-cast shared links"
            setTextColor(Color.WHITE)
            textSize = 16f
            buttonTintList = android.content.res.ColorStateList.valueOf(color(PRIMARY))
            setPadding(0, dp(12), 0, 0)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_AUTO_CAST, isChecked).apply()
            }
        }
        content.addView(autoCastCheck)

        content.addView(label("Video URL"))
        content.addView(helper("Paste or share a YouTube link, supported video page, or direct video URL."))
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

        localVideoButton = button("Cast Local Video", large = true) { castLocalVideoOrPick() }
        content.addView(localVideoButton)
        localVideoStatusText = TextView(this).apply {
            text = "Your phone and glasses must be on the same Wi-Fi network.\nKeep this phone nearby while the video is playing."
            setTextColor(color(MUTED))
            textSize = 14f
            setPadding(0, dp(8), 0, dp(2))
        }
        content.addView(localVideoStatusText)

        content.addView(TextView(this).apply {
            text = "Timeline"
            setTextColor(color(MUTED))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(8))
        })

        nowPlayingText = TextView(this).apply {
            text = "Now playing"
            setTextColor(Color.WHITE)
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
            text = "Timeline unavailable"
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
        content.addView(button("Seek -10s") { sendPlaybackCommand("seekBack") })
        content.addView(button("Seek +10s") { sendPlaybackCommand("seekForward") })
        content.addView(button("Stop") { sendPlaybackCommand("stop") })

        statusText = TextView(this).apply {
            text = ""
            setTextColor(color(MUTED))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        content.addView(statusText)

        lastSentUrlText = debugText("Last sent URL: ")
        content.addView(lastSentUrlText)

        lastResponseText = debugText("Last response: ")
        content.addView(lastResponseText)

        setContentView(root)
    }

    private fun setupEntryCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(SURFACE))
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
                setTextColor(Color.WHITE)
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "Add the GlassCast receiver to your Meta Ray-Ban Display glasses."
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
            setBackgroundColor(color(SURFACE))
            setPadding(dp(16), dp(18), dp(16), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            }

            // Future auth/license gate can be added here, but setup instructions should
            // remain available before login.
            addView(TextView(this@MainActivity).apply {
                text = "Set up Meta Display"
                setTextColor(Color.WHITE)
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@MainActivity).apply {
                text = "GlassCast runs as a Web App on your Meta Ray-Ban Display glasses. First enable Developer Mode in the Meta AI app, then add the GlassCast receiver URL. After that, pair your glasses with this phone app using the session code."
                setTextColor(color(MUTED))
                textSize = 16f
                setPadding(0, dp(10), 0, dp(12))
            })

            addView(setupSectionTitle("1. Enable Developer Mode"))
            addView(setupBody("Developer Mode allows your Meta AI app to add Web Apps to your Meta Ray-Ban Display glasses."))
            numberedSteps(
                listOf(
                    "Update the Meta AI app to the latest version.",
                    "Update your glasses firmware in the Meta AI app if an update is available.",
                    "Open the Meta AI app on your phone.",
                    "Go to Settings.",
                    "Open App Info.",
                    "Tap the app version number five times.",
                    "When prompted, enable Developer Mode.",
                    "Go back to Settings after Developer Mode is enabled."
                )
            ).forEach { addView(it) }
            addView(setupNote("If you do not see Developer Mode or Web Apps, make sure the Meta AI app and glasses firmware are fully updated."))

            addView(setupSectionTitle("2. Add GlassCast as a Web App"))
            addView(setupBody("After Developer Mode is enabled, add the GlassCast receiver URL to your glasses."))
            numberedSteps(
                listOf(
                    "Open the Meta AI app.",
                    "Go to your Meta Ray-Ban Display glasses settings.",
                    "Open App connections.",
                    "Choose Web Apps.",
                    "Tap Add a Web App.",
                    "For the app name, enter: GlassCast.",
                    "For the URL, paste: $RECEIVER_URL",
                    "Save or add the Web App.",
                    "Open GlassCast from your glasses app list.",
                    "You should see a session code on the glasses."
                )
            ).forEach { addView(it) }

            addView(button("Copy Receiver URL") { copyReceiverUrl() })
            setupCopyStatusText = TextView(this@MainActivity).apply {
                text = ""
                setTextColor(color(PRIMARY))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }
            addView(setupCopyStatusText)
            addView(button("Open Receiver URL") { openExternalUrl(RECEIVER_URL) })
            addView(button("View Meta Setup Docs") { openExternalUrl(META_SETUP_DOCS_URL) })

            addView(setupSectionTitle("3. Pair your phone"))
            numberedSteps(
                listOf(
                    "Open GlassCast on your glasses.",
                    "Find the session code shown on the glasses.",
                    "Enter that code into this Android app.",
                    "Tap Save or Pair.",
                    "Paste or share a YouTube link.",
                    "Tap Cast Video.",
                    "The video should appear on your glasses."
                )
            ).forEach { addView(it) }
            addView(setupNote("Once paired, you can share a YouTube video to GlassCast from the Android share menu."))

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
                    "I do not see Web Apps",
                    "Developer Mode may not be enabled. Enable Developer Mode first, then return to App connections.",
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
                    "Live streams may not have a normal timeline. Play/Pause and Stop may still work.",
                    "",
                    "Local phone videos do not cast",
                    "Make sure this phone and the glasses are on the same Wi-Fi network. Keep this phone nearby while the video is playing."
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
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, dp(4), 0, dp(4))
            }
        }

    private fun setupSectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
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
        setTextColor(color(PRIMARY))
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
                setTextColor(if (isHeading) Color.WHITE else color(MUTED))
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

    private fun copyReceiverUrl() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GlassCast receiver URL", RECEIVER_URL))
        setupCopyStatusText.text = "Receiver URL copied."
    }

    private fun openExternalUrl(url: String) {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        runCatching { startActivity(intent) }
            .onFailure { showStatus("No browser found", isError = true) }
    }

    private fun castVideo() {
        val code = sessionCodeOrNull() ?: return showStatus("Missing session code", isError = true)
        val rawInput = urlInput.text.toString()
        val sanitizedUrl = sanitizeVideoUrl(rawInput)
        Log.d(TAG, "raw input: $rawInput")
        Log.d(TAG, "sanitized URL: $sanitizedUrl")
        videoUrl = sanitizedUrl
        setVideoUrl(sanitizedUrl)
        if (sanitizedUrl.isBlank()) return showStatus("Missing URL", isError = true)
        updateLastSentUrl(sanitizedUrl)

        postJson(
            JSONObject()
                .put("code", code)
                .put("type", "cast")
                .put("url", sanitizedUrl)
        )
    }

    private fun castLocalVideoOrPick() {
        if (localVideoUri == null) {
            openLocalVideoPicker()
            return
        }
        castLocalVideo()
    }

    private fun openLocalVideoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, PICK_LOCAL_VIDEO_REQUEST) }
            .onFailure { showStatus("Could not open video picker", isError = true) }
    }

    private fun prepareLocalVideo(uri: Uri, castAfterReady: Boolean, openedFromLocalShare: Boolean) {
        localVideoUri = uri
        openedFromShare = openedFromLocalShare
        primaryButton.text = "Cast Video"
        localVideoButton.text = "Cast Local Video"

        val result = runCatching { localVideoServer.serve(uri) }
            .getOrElse {
                localVideoUri = null
                localVideoUrl = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                localVideoStatusText.text = "Could not start local video stream. Make sure your phone and glasses are on the same network."
                showStatus("Could not start local video stream. Make sure your phone and glasses are on the same network.", isError = true)
                return
            }

        localVideoUrl = result.url
        localVideoName = result.displayName
        localVideoLength = result.contentLength
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // TODO: Move LocalVideoServer into a foreground service with a persistent
        // notification before relying on it for long background casting sessions.
        val lengthWarning = if (localVideoLength < 0) "\nVideo length is unknown, so seeking may be limited." else ""
        localVideoStatusText.text =
            "Serving local video from this phone\n$localVideoName\nReady to cast local video.\nYour phone and glasses must be on the same Wi-Fi network.\nKeep this phone nearby while the video is playing.$lengthWarning"
        showStatus("Ready to cast local video.")
        updateSessionSummary()

        if (castAfterReady) {
            castLocalVideo()
        }
    }

    private fun castLocalVideo() {
        val uri = localVideoUri
        if (uri == null) {
            openLocalVideoPicker()
            return
        }

        val serverUrl = localVideoUrl ?: run {
            prepareLocalVideo(uri, castAfterReady = false, openedFromLocalShare = openedFromShare)
            localVideoUrl
        } ?: return

        val code = sessionCodeOrNull() ?: return showStatus("Missing session code", isError = true)
        videoUrl = serverUrl
        updateLastSentUrl(serverUrl)
        localVideoStatusText.text =
            "Serving local video from this phone\n$localVideoName\nYour phone and glasses must be on the same Wi-Fi network.\nKeep this phone nearby while the video is playing."

        postJson(
            JSONObject()
                .put("code", code)
                .put("type", "cast")
                .put("url", serverUrl)
        )
    }

    private fun sendPlaybackCommand(command: String) {
        val code = sessionCodeOrNull() ?: return showStatus("Missing session code", isError = true)

        postJson(
            JSONObject()
                .put("code", code)
                .put("type", "command")
                .put("command", command)
        )
    }

    private fun sendSeekTo(seconds: Double) {
        val code = sessionCodeOrNull()
        if (code == null) {
            isUserScrubbing = false
            return showStatus("Missing session code", isError = true)
        }
        if (!isKnownDuration(timelineDurationSeconds)) {
            isUserScrubbing = false
            return showTimelineUnavailable("Timeline unavailable")
        }

        optimisticCurrentSeconds = clampTime(seconds, timelineDurationSeconds)
        ignorePlaybackPositionUntilMs = System.currentTimeMillis() + SEEK_POSITION_IGNORE_MS
        postJson(
            JSONObject()
                .put("code", code)
                .put("type", "command")
                .put("command", "seekTo")
                .put("time", seconds)
        ) {
            pollHandler.postDelayed({
                isUserScrubbing = false
                fetchPlaybackState()
            }, SCRUB_RELEASE_DELAY_MS)
        }
    }

    private fun postJson(payload: JSONObject) {
        postJson(payload, afterComplete = null)
    }

    private fun postJson(payload: JSONObject, afterComplete: (() -> Unit)?) {
        showStatus("Sending...")
        val jsonBody = payload.toString()
        Log.d(TAG, "JSON body sent: $jsonBody")
        val request = Request.Builder()
            .url(SESSION_ENDPOINT)
            .post(jsonBody.toRequestBody(jsonType))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val error = "Error: ${e.localizedMessage ?: "network failure"}"
                runOnUiThread {
                    updateLastResponse(error)
                    showStatus(error, isError = true)
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
                            showStatus("Sent")
                        } else {
                            showStatus(apiError(body, it.code), isError = true)
                        }
                        afterComplete?.invoke()
                    }
                }
            }
        })
    }

    private fun startPlaybackPolling() {
        if (normalizeSessionCode(codeInput.text.toString()).isBlank()) {
            showTimelineUnavailable("Timeline unavailable")
            return
        }
        if (isPolling) return
        isPolling = true
        pollHandler.post(pollRunnable)
    }

    private fun restartPlaybackPolling() {
        if (normalizeSessionCode(codeInput.text.toString()).isBlank()) {
            stopPlaybackPolling()
            showTimelineUnavailable("Timeline unavailable")
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
            runOnUiThread { showTimelineUnavailable("Timeline unavailable") }
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
                    runOnUiThread { showStatus("Error: playback state unavailable", isError = true) }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        if (fallbackToSessionEndpoint) {
                            fetchPlaybackStateFrom("$SESSION_ENDPOINT?code=${Uri.encode(code)}", fallbackToSessionEndpoint = false)
                        } else {
                            runOnUiThread { showStatus("Error: playback state unavailable", isError = true) }
                        }
                        return
                    }

                    val state = runCatching { parsePlaybackState(JSONObject(body)) }.getOrNull()
                    runOnUiThread {
                        if (state == null) {
                            showTimelineUnavailable("Timeline unavailable")
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
            findString(stateJson, "mode", "status", "playbackStatus", "state", "playerState")
                ?: findString(json, "mode", "status", "playbackStatus", "state", "playerState")
        )
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
            title = title,
            url = url
        )
    }

    private fun applyPlaybackState(state: PlaybackState) {
        nowPlayingText.text = state.title?.takeIf { it.isNotBlank() } ?: "Now playing"

        if (!isKnownDuration(state.duration)) {
            val message = if (state.mode.equals("youtube", ignoreCase = true)) {
                "Live or timeline unavailable"
            } else {
                "Timeline unavailable"
            }
            showTimelineUnavailable(message, durationLabel = if (state.playing) "LIVE" else "--:--")
            return
        }

        timelineDurationSeconds = state.duration
        timelineSeekBar.isEnabled = true
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

    private fun showTimelineUnavailable(message: String, durationLabel: String = "--:--") {
        timelineDurationSeconds = 0.0
        optimisticCurrentSeconds = null
        ignorePlaybackPositionUntilMs = 0L
        if (!isUserScrubbing) {
            timelineSeekBar.progress = 0
            currentTimeText.text = "0:00"
        }
        isUserScrubbing = false
        timelineSeekBar.isEnabled = false
        durationText.text = durationLabel
        timelineStatusText.text = message
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
            findDouble(json, "duration", "totalDuration", "length") != null

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
        val message = runCatching {
            val json = JSONObject(body)
            json.optString("error").ifBlank { json.optString("message") }
        }.getOrNull()

        return if (!message.isNullOrBlank()) {
            "Error: $message"
        } else {
            "Error: HTTP $statusCode"
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
        if (type.equals("text/plain", ignoreCase = true)) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                ?: ""
            val url = sanitizeVideoUrl(sharedText)
            Log.d(TAG, "raw input: $sharedText")
            Log.d(TAG, "sanitized URL: $url")
            if (url.isBlank()) {
                showStatus("Missing URL", isError = true)
            } else {
                useSharedUrl(url)
            }
            return
        }

        if (type.startsWith("video/", ignoreCase = true) || isContentUri(streamUri(intent))) {
            val uri = streamUri(intent)
            if (uri == null) {
                showStatus("Could not open shared video", isError = true)
                return
            }
            takeReadPermission(uri, intent.flags)
            prepareLocalVideo(uri, castAfterReady = false, openedFromLocalShare = true)
        }
    }

    private fun handleViewIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            val rawInput = uri.toString()
            val url = sanitizeVideoUrl(rawInput)
            Log.d(TAG, "raw input: $rawInput")
            Log.d(TAG, "sanitized URL: $url")
            if (url.isBlank()) {
                showStatus("Missing URL", isError = true)
            } else {
                useSharedUrl(url)
            }
        }
    }

    private fun useSharedUrl(url: String) {
        openedFromShare = true
        setVideoUrl(url)
        primaryButton.text = "Cast Shared Video"
        updateSessionSummary()

        val code = normalizeSessionCode(codeInput.text.toString())
        if (code.isBlank()) {
            showStatus("Missing session code", isError = true)
            return
        }

        setSessionCode(code)
        showStatus("Ready")
        if (autoCastCheck.isChecked) {
            castVideo()
        }
    }

    private fun sanitizeVideoUrl(input: String): String {
        val unquoted = input.trim().trimSurroundingQuotes()
        val url = URL_PATTERN.find(unquoted)?.value ?: unquoted
        return url.trim().trimEnd(')', ']', '}', ',', ';', '"', '\'')
    }

    private fun String.trimSurroundingQuotes(): String {
        if (length < 2) return this
        val first = first()
        val last = last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            substring(1, length - 1).trim()
        } else {
            this
        }
    }

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
            readySummary.text = "Open https://glasscast.znichka.xyz and enter the receiver session code."
        } else {
            sessionSummary.text = "Session $code"
            readySummary.text = if (localVideoUri != null) {
                "Ready to cast local video."
            } else if (openedFromShare && videoUrl.isNotBlank()) {
                "Ready to cast to session $code"
            } else {
                "Ready"
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

    private fun takeReadPermission(uri: Uri, flags: Int) {
        val readFlag = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlag == 0) return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        setTextColor(Color.WHITE)
        setHintTextColor(color(MUTED))
        textSize = 18f
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(color(SURFACE))
    }

    private fun button(text: String, large: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = if (large) 20f else 17f
            isAllCaps = false
            setTextColor(Color.BLACK)
            setBackgroundColor(color(if (large) PRIMARY else PRIMARY_DARK))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (large) dp(64) else dp(54)
            ).apply {
                topMargin = if (large) dp(22) else dp(10)
            }
        }

    private fun showStatus(message: String, isError: Boolean = false) {
        statusText.text = message
        statusText.setTextColor(color(if (isError) DANGER else MUTED))
    }

    private fun updateLastSentUrl(url: String) {
        lastSentUrl = url
        lastSentUrlText.text = "Last sent URL: $lastSentUrl"
    }

    private fun updateLastResponse(response: String) {
        lastResponse = response
        lastResponseText.text = "Last response: $lastResponse"
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
        val title: String?,
        val url: String?
    )

    companion object {
        private const val API_BASE_URL = "https://glasscast.znichka.xyz"
        private const val RECEIVER_URL = API_BASE_URL
        private const val SESSION_ENDPOINT = "$API_BASE_URL/api/session"
        private const val STATE_ENDPOINT = "$API_BASE_URL/api/session/state"
        private const val META_SETUP_DOCS_URL = "https://wearables.developer.meta.com/docs/develop/webapps/setup/"
        private const val META_TEST_DOCS_URL = "https://wearables.developer.meta.com/docs/develop/webapps/test"
        private val TROUBLESHOOTING_TITLES = setOf(
            "I do not see Developer Mode",
            "I do not see Web Apps",
            "The receiver URL will not open",
            "The session code does not work",
            "YouTube video does not play",
            "Live video timeline is unavailable",
            "Local phone videos do not cast"
        )
        private const val PREFS_NAME = "glasscast"
        private const val KEY_SESSION_CODE = "session_code"
        private const val KEY_AUTO_CAST = "auto_cast_shared_links"
        private const val PLAYBACK_POLL_MS = 1_000L
        private const val SCRUB_RELEASE_DELAY_MS = 500L
        private const val SEEK_POSITION_IGNORE_MS = 800L
        private const val SEEK_BAR_MAX = 1_000
        private const val PICK_LOCAL_VIDEO_REQUEST = 2001
        private const val TAG = "GlassCast"

        private const val BG = "#101114"
        private const val SURFACE = "#1B1D22"
        private const val MUTED = "#B8BDC7"
        private const val PRIMARY = "#34D399"
        private const val PRIMARY_DARK = "#10B981"
        private const val DANGER = "#F97373"

        private val URL_PATTERN = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    }
}
