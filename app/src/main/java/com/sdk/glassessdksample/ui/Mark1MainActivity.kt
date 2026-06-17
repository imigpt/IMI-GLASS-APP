package com.sdk.glassessdksample.ui

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.*
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.sdk.glassessdksample.RemoteConfigManager
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import com.sdk.glassessdksample.ListeningService
import com.sdk.glassessdksample.NotificationListener
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.SettingsActivity
import com.sdk.glassessdksample.databinding.ActivityMark1MainBinding
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Locale

class Mark1MainActivity : AppCompatActivity(), GeminiLiveService.GeminiLiveCallbacks {

    companion object {
        private const val TAG = "Mark1MainActivity"
        private const val PREF_VOICE_MODE = "voice_mode"
        private const val VOICE_MODE_SEAMLESS = "seamless"
        private const val VOICE_MODE_SINGLE_SHOT = "single_shot"
        private const val REQUEST_PERMISSIONS = 1001
        private const val PREFS_CONVERSATION = "mark1_conversation"
        private const val KEY_HISTORY = "conversation_history"
        private const val KEY_TIMESTAMP = "conversation_timestamp"
        private const val HISTORY_TTL_MS = 60 * 60 * 1000L
        private const val MAX_HISTORY_PAIRS = 100
    }

    private lateinit var binding: ActivityMark1MainBinding

    private var geminiLiveService: GeminiLiveService? = null
    private var notesManager: QuickNotesManager? = null
    private var meetingManager: MeetingMinutesManager? = null
    private lateinit var userMemoryManager: UserMemoryManager

    private var tts: TextToSpeech? = null
    private var itunesMediaPlayer: MediaPlayer? = null
    private val musicProgressHandler = Handler(Looper.getMainLooper())
    private var pulseAnimator: AnimatorSet? = null

    private var isGeminiLiveActive = false
    private var wakeWordStarted = false
    private var isAiMuted = false

    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private val gson = Gson()

    // Id of the conversation session currently being recorded (null when idle).
    private var currentSessionId: String? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryStatusStore.EXTRA_BATTERY_LEVEL, -1) ?: return
            if (level in 0..100) updateBatteryChip(level)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMark1MainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userMemoryManager = UserMemoryManager(this)
        notesManager = QuickNotesManager(this)
        meetingManager = MeetingMinutesManager(this)

        // Fetch API keys securely from Firebase Remote Config (same as Mark 2's MainActivity)
        RemoteConfigManager.fetchAndActivate { success ->
            Log.d(TAG, if (success) "✅ Remote config loaded" else "⚠️ Using cached remote config")
        }

        loadConversationHistory()
        initTts()
        initGeminiLive()
        setupActions()
        setupBottomNav()
        preWarmWakeWord()
        checkAndRequestPermissions()
        checkBleAndShowGate()
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(batteryReceiver, IntentFilter(BatteryStatusStore.ACTION_BATTERY_UPDATED))
        binding.bottomNavigation.selectedItemId = R.id.nav_home
        checkBleAndShowGate()
    }

    override fun onPause() {
        super.onPause()
        EventBus.getDefault().unregister(this)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(batteryReceiver)
        saveConversationHistory()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        itunesMediaPlayer?.release()
        musicProgressHandler.removeCallbacksAndMessages(null)
        pulseAnimator?.cancel()
        geminiLiveService?.stopLiveConversation()
        HotHelper.getInstance(applicationContext).stop()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INITIALISATION
    // ─────────────────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
    }

    private fun initGeminiLive() {
        geminiLiveService = GeminiLiveService(applicationContext, this)
    }

    private fun preWarmWakeWord() {
        HotHelper.getInstance(applicationContext).apply {
            setPreferGlassBleAudio(false)
        }
    }

    private fun setupBottomNav() {
        Mark1BottomNavManager.setup(this, binding.bottomNavigation, R.id.nav_home)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BLE GATE
    // ─────────────────────────────────────────────────────────────────────────

    private fun checkBleAndShowGate() {
        binding.layoutBleGate.visibility = View.VISIBLE
        binding.layoutBleChecking.visibility = View.VISIBLE
        binding.layoutBleNotConnected.visibility = View.GONE

        Handler(Looper.getMainLooper()).postDelayed({
            if (isGlassConnected()) {
                hideBleGate()
            } else {
                binding.layoutBleChecking.visibility = View.GONE
                binding.layoutBleNotConnected.visibility = View.VISIBLE
            }
        }, 800)
    }

    private fun isGlassConnected(): Boolean {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (!adapter.isEnabled) return false
            val hfpState = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
            if (hfpState == BluetoothProfile.STATE_CONNECTED) return true
        } catch (e: Exception) {
            Log.w(TAG, "BLE check error: ${e.message}")
        }
        return false
    }

    private fun hideBleGate() {
        binding.layoutBleGate.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction {
                binding.layoutBleGate.visibility = View.GONE
                binding.layoutBleGate.alpha = 1f
            }
            .start()
        binding.bottomNavigation.visibility = View.VISIBLE
        startEntranceAnimations()
        if (!isAiMuted) startWakeWordListening()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENTRANCE ANIMATIONS
    // ─────────────────────────────────────────────────────────────────────────

    private fun startEntranceAnimations() {
        val slideFadeIn = AnimationUtils.loadAnimation(this, R.anim.slide_fade_in)
        val views = listOf(
            binding.cardHero,
            binding.tvQuickActionsLabel,
            binding.rowTiles1,
            binding.rowTiles2,
            binding.rowTiles3
        )
        views.forEachIndexed { i, view ->
            view.alpha = 0f
            view.postDelayed({
                view.alpha = 1f
                view.startAnimation(slideFadeIn)
            }, (i * 100L))
        }

        // Floating animation on glasses image
        val floatAnim = ObjectAnimator.ofFloat(binding.imgGlasses, "translationY", 0f, -10f * resources.displayMetrics.density).apply {
            duration = 2200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        floatAnim.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WAKE WORD
    // ─────────────────────────────────────────────────────────────────────────

    private fun startWakeWordListening() {
        if (isAiMuted || wakeWordStarted) return
        wakeWordStarted = true

        try {
            val serviceIntent = Intent(this, ListeningService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start ListeningService: ${e.message}")
        }

        HotHelper.getInstance(applicationContext).start()
    }

    private fun stopWakeWordListening() {
        wakeWordStarted = false
        HotHelper.getInstance(applicationContext).stop()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONVERSATION
    // ─────────────────────────────────────────────────────────────────────────

    private fun playChimeThenStartConversation() {
        stopWakeWordListening()
        try {
            val mp = MediaPlayer.create(this, R.raw.bmw_warning_chime)
            mp?.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp?.setOnCompletionListener { player ->
                player.release()
                startInlineGeminiLive()
            }
            mp?.start()
        } catch (e: Exception) {
            Log.w(TAG, "Chime failed: ${e.message}")
            startInlineGeminiLive()
        }
    }

    private fun playChimeManuallyThenStartConversation() {
        if (isGeminiLiveActive) {
            stopConversation()
            return
        }
        playChimeThenStartConversation()
    }

    private fun startInlineGeminiLive() {
        if (isGeminiLiveActive) return
        isGeminiLiveActive = true

        // Begin a new conversation session so every turn in this run is grouped
        // together (date/time stamped) in the History screen.
        currentSessionId = ConversationSessionStore.startSession(this)

        val systemInstruction = buildSystemInstruction()

        runOnUiThread {
            binding.cardConversation.visibility = View.VISIBLE
            binding.tvConversationStatus.text = "🎤 Listening…"
            binding.btnQuickStart.text = "Stop Listening"
            startPulseAnimation()
        }

        geminiLiveService?.startLiveConversation(systemInstruction)
    }

    private fun stopConversation() {
        isGeminiLiveActive = false
        // End the current session so the next conversation is grouped separately.
        currentSessionId = null
        geminiLiveService?.stopLiveConversation()
        stopPulseAnimation()

        runOnUiThread {
            binding.tvConversationStatus.text = "Ready"
            binding.btnQuickStart.text = "Quick Start"
        }

        if (!isAiMuted && isGlassConnected()) {
            startWakeWordListening()
        } else if (!isAiMuted) {
            startWakeWordListening()
        }
    }

    private fun buildSystemInstruction(): String {
        val memory = userMemoryManager.getUserMemory()
        val notes = notesManager?.getNotesContextForAI() ?: ""
        val history = buildHistorySummary()

        val sb = StringBuilder()
        sb.append("You are IMI, an intelligent AI assistant built into smart glasses. ")
        sb.append("You speak naturally, concisely, and helpfully. ")
        sb.append("You are aware of your environment through what the user shares with you.\n\n")

        if (!memory.isEmpty()) {
            sb.append("## User Profile\n")
            if (memory.userName.isNotBlank()) sb.append("Name: ${memory.userName}\n")
            if (memory.occupation.isNotBlank()) sb.append("Occupation: ${memory.occupation}\n")
            if (memory.location.isNotBlank()) sb.append("Location: ${memory.location}\n")
            if (memory.likes.isNotEmpty()) sb.append("Likes: ${memory.likes.joinToString(", ")}\n")
            if (memory.interests.isNotEmpty()) sb.append("Interests: ${memory.interests.joinToString(", ")}\n")
            if (memory.autoLearnedFacts.isNotEmpty()) sb.append("Known facts: ${memory.autoLearnedFacts.takeLast(10).joinToString("; ")}\n")
            sb.append("\n")
        }

        if (history.isNotBlank()) {
            sb.append("## Recent Conversation\n$history\n\n")
        }

        if (notes.isNotBlank()) {
            sb.append("## User's Quick Notes\n$notes\n\n")
        }

        sb.append("## Available Tools\n")
        sb.append("You have access to these tools: create_note, capture_photo_note, start_meeting, ")
        sb.append("play_music, play_youtube, make_phone_call, get_directions, get_weather, ")
        sb.append("web_search, define_word, wiki_summary, stock_quote, mute_ai, ")
        sb.append("read_notifications, say_goodbye.\n\n")
        sb.append("Use tools when the user's request matches them. Keep responses brief and spoken-word friendly.")

        return sb.toString()
    }

    private fun buildHistorySummary(): String {
        val recent = conversationHistory.takeLast(10)
        if (recent.isEmpty()) return ""
        return recent.joinToString("\n") { (user, ai) -> "User: $user\nIMI: $ai" }
    }

    private fun addToHistory(userText: String, aiText: String) {
        if (userText.isBlank() && aiText.isBlank()) return
        conversationHistory.add(Pair(userText, aiText))
        if (conversationHistory.size > MAX_HISTORY_PAIRS) {
            conversationHistory.removeAt(0)
        }
        // Persist the completed turn into the current conversation session so the
        // whole conversation is grouped and saved (not just the last few turns).
        // If no session is active yet (e.g. wake-word triggered before the UI
        // started one), the store creates one so the turn is never lost.
        if (currentSessionId == null) {
            currentSessionId = ConversationSessionStore.startSession(this)
        }
        ConversationSessionStore.appendTurn(this, currentSessionId, userText, aiText)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONVERSATION HISTORY PERSISTENCE
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveConversationHistory() {
        if (conversationHistory.isEmpty()) return
        val prefs = getSharedPreferences(PREFS_CONVERSATION, Context.MODE_PRIVATE)
        val json = gson.toJson(conversationHistory)
        prefs.edit()
            .putString(KEY_HISTORY, json)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    private fun loadConversationHistory() {
        val prefs = getSharedPreferences(PREFS_CONVERSATION, Context.MODE_PRIVATE)
        val savedAt = prefs.getLong(KEY_TIMESTAMP, 0L)
        if (System.currentTimeMillis() - savedAt > HISTORY_TTL_MS) {
            prefs.edit().remove(KEY_HISTORY).remove(KEY_TIMESTAMP).apply()
            return
        }
        val json = prefs.getString(KEY_HISTORY, null) ?: return
        try {
            val type = object : TypeToken<List<Pair<String, String>>>() {}.type
            val loaded: List<Pair<String, String>> = gson.fromJson(json, type)
            conversationHistory.addAll(loaded)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load history: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PULSE ANIMATION
    // ─────────────────────────────────────────────────────────────────────────

    private fun startPulseAnimation() {
        val ring = binding.viewPulseRing
        ring.visibility = View.VISIBLE
        pulseAnimator?.cancel()
        pulseAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(ring, "scaleX", 1f, 2.2f).apply {
                    duration = 900; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.RESTART
                },
                ObjectAnimator.ofFloat(ring, "scaleY", 1f, 2.2f).apply {
                    duration = 900; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.RESTART
                },
                ObjectAnimator.ofFloat(ring, "alpha", 0.7f, 0f).apply {
                    duration = 900; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.RESTART
                }
            )
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.viewPulseRing.visibility = View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MUTE TOGGLE
    // ─────────────────────────────────────────────────────────────────────────

    private fun toggleAiMute() {
        isAiMuted = !isAiMuted
        HotHelper.getInstance(applicationContext).setMuted(isAiMuted)

        if (isAiMuted) {
            stopWakeWordListening()
            if (isGeminiLiveActive) stopConversation()
            binding.tvMuteLabel.text = "Unmute AI"
            Toast.makeText(this, "AI muted — not listening", Toast.LENGTH_SHORT).show()
        } else {
            binding.tvMuteLabel.text = "Silent Mode"
            if (isGlassConnected()) {
                checkBleAndShowGate()
            } else {
                startWakeWordListening()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BATTERY UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateBatteryChip(level: Int) {
        binding.tvHomeBatteryLevel.text = "$level%"
        binding.layoutBatteryChip.visibility = View.VISIBLE

        val minutes = (level * 150) / 100
        val h = minutes / 60
        val m = minutes % 60
        binding.tvDeviceBatteryTime.text = if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TILE PRESS ANIMATION
    // ─────────────────────────────────────────────────────────────────────────

    private fun animateTilePress(view: View, action: () -> Unit) {
        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).withEndAction {
            view.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(80).withEndAction {
                    action()
                }.start()
            }.start()
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SETUP CLICK LISTENERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupActions() {
        binding.btnQuickStart.setOnClickListener {
            playChimeManuallyThenStartConversation()
        }

        binding.imgProfileAvatar.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnBleGateRetry.setOnClickListener {
            checkBleAndShowGate()
        }

        binding.btnBleGateInfo.setOnClickListener {
            showConnectionGuideDialog()
        }

        binding.btnGlassControls.setOnClickListener {
            animateTilePress(it) {
                startActivity(Intent(this, DeviceBindActivity::class.java))
            }
        }

        binding.btnSunny.setOnClickListener {
            animateTilePress(it) {
                startActivity(Intent(this, DeviceBindActivity::class.java))
            }
        }

        binding.btnMuteAi.setOnClickListener {
            animateTilePress(it) { toggleAiMute() }
        }

        binding.btnQuickNotes.setOnClickListener {
            animateTilePress(it) {
                startActivity(Intent(this, QuickNotesActivity::class.java))
            }
        }

        binding.btnMeetingMinutes.setOnClickListener {
            animateTilePress(it) {
                startActivity(Intent(this, MeetingMinutesActivity::class.java))
            }
        }

        binding.btnConversationHistory.setOnClickListener {
            animateTilePress(it) {
                startActivity(Intent(this, ConversationHistoryActivity::class.java))
            }
        }

        // "Controls" tile — opens device controls
        binding.btnNotifications.setOnClickListener {
            animateTilePress(it) {
                startActivity(Intent(this, DeviceBindActivity::class.java))
            }
        }

        // Music player controls
        binding.btnMusicPlayPause.setOnClickListener {
            itunesMediaPlayer?.let { mp ->
                if (mp.isPlaying) { mp.pause(); binding.btnMusicPlayPause.text = "Play" }
                else { mp.start(); binding.btnMusicPlayPause.text = "Pause" }
            }
        }

        binding.btnMusicStop.setOnClickListener {
            itunesMediaPlayer?.stop()
            itunesMediaPlayer?.release()
            itunesMediaPlayer = null
            musicProgressHandler.removeCallbacksAndMessages(null)
            binding.cardMusicPlayer.visibility = View.GONE
        }
    }

    private fun showConnectionGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("Connect Your IMI Glasses")
            .setMessage(
                "1. Open phone Bluetooth settings\n\n" +
                "2. Power on IMI glasses\n\n" +
                "3. Pair the device\n\n" +
                "4. Return here and tap Retry"
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun handleNotificationsTile() {
        if (!isNotificationListenerEnabled()) {
            val prefs = getSharedPreferences("imi_prefs", Context.MODE_PRIVATE)
            val alreadyAsked = prefs.getBoolean("notification_listener_asked", false)
            if (!alreadyAsked) {
                prefs.edit().putBoolean("notification_listener_asked", true).apply()
                AlertDialog.Builder(this)
                    .setTitle("Notification Access")
                    .setMessage("Enable notification access so IMI can read your notifications.")
                    .setPositiveButton("Enable") { _, _ ->
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    .setNegativeButton("Not Now", null)
                    .show()
            } else {
                Toast.makeText(this, "Enable notification access in Settings", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Notifications: ask IMI \"What notifications do I have?\"", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, NotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EVENTBUS
    // ─────────────────────────────────────────────────────────────────────────

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onBluetoothEvent(event: BluetoothEvent) {
        when (event.type) {
            BluetoothEvent.EventType.CONNECTED -> {
                hideBleGate()
            }
            BluetoothEvent.EventType.DISCONNECTED -> {
                if (isGeminiLiveActive) stopConversation()
                checkBleAndShowGate()
            }
            BluetoothEvent.EventType.VOICE_TEXT -> {
                val data = event.data as? String ?: return
                if (data == "wake up" && !isGeminiLiveActive && !isAiMuted) {
                    playChimeThenStartConversation()
                }
            }
            BluetoothEvent.EventType.BATTERY_LEVEL -> {
                val level = (event.data as? Int) ?: return
                BatteryStatusStore.saveBatteryLevel(applicationContext, level)
            }
            else -> {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GEMINI LIVE CALLBACKS
    // ─────────────────────────────────────────────────────────────────────────

    override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {
        runOnUiThread {
            val statusText = when {
                input.isNotBlank() -> "🎤 You: $input"
                output.isNotBlank() -> "🔊 Imi: $output"
                else -> "🎤 Listening…"
            }
            binding.tvConversationStatus.text = statusText

            if (isFinal && (input.isNotBlank() || output.isNotBlank())) {
                val current = binding.tvConversation.text.toString()
                val line = when {
                    input.isNotBlank() && output.isNotBlank() -> "You: $input\nIMI: $output\n"
                    input.isNotBlank() -> "You: $input\n"
                    else -> "IMI: $output\n"
                }
                binding.tvConversation.text = if (current.isBlank()) line else "$current$line"
                binding.scrollConversation.post {
                    binding.scrollConversation.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        if (isFinal && input.isNotBlank()) {
            userMemoryManager.learnFromUserMessage(input)
            userMemoryManager.incrementMessageStats(false)
        }
    }

    override fun onTurnComplete(fullInput: String, fullOutput: String) {
        addToHistory(fullInput, fullOutput)

        if (isSingleShotMode()) {
            runOnUiThread { stopConversation() }
        }
    }

    override fun onToolCall(toolName: String, args: Map<String, Any>): String {
        Log.d(TAG, "Tool call: $toolName args=$args")
        return when (toolName) {
            "create_note" -> handleCreateNote(args)
            "start_meeting" -> handleStartMeeting()
            "play_music" -> handlePlayMusic(args)
            "play_youtube" -> handlePlayYoutube(args)
            "make_phone_call" -> handlePhoneCall(args)
            "get_directions" -> handleDirections(args)
            "get_weather" -> handleWeather(args)
            "web_search" -> handleWebSearch(args)
            "define_word" -> handleDefineWord(args)
            "wiki_summary" -> handleWikiSummary(args)
            "stock_quote" -> handleStockQuote(args)
            "mute_ai" -> handleMuteAi()
            "read_notifications" -> handleReadNotifications()
            "say_goodbye" -> handleSayGoodbye()
            else -> "Tool $toolName not yet implemented."
        }
    }

    override fun onAudioPlaybackStart() {
        runOnUiThread {
            binding.tvConversationStatus.text = "🔊 Imi speaking…"
        }
    }

    override fun onAudioPlaybackEnd() {
        runOnUiThread {
            if (isGeminiLiveActive) binding.tvConversationStatus.text = "🎤 Listening…"
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, "GeminiLive error: $error")
        runOnUiThread {
            Toast.makeText(this, "Connection error — tap Quick Start to retry", Toast.LENGTH_SHORT).show()
            stopConversation()
        }
    }

    override fun onConnectionStatusChanged(isConnected: Boolean) {
        Log.d(TAG, "Gemini connection: $isConnected")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL HANDLERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleCreateNote(args: Map<String, Any>): String {
        val title = args["title"] as? String ?: "Note"
        val content = args["content"] as? String ?: ""
        notesManager?.createNote(title, content, QuickNote.CreatedBy.AI)
        runOnUiThread { Toast.makeText(this, "Note saved: $title", Toast.LENGTH_SHORT).show() }
        return "Note saved successfully."
    }

    private fun handleStartMeeting(): String {
        runOnUiThread {
            stopConversation()
            startActivity(Intent(this, ActiveMeetingActivity::class.java))
        }
        return "Opening meeting minutes."
    }

    private fun handlePlayMusic(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: args["song"] as? String ?: return "Please specify a song."
        runOnUiThread {
            Toast.makeText(this, "Searching for: $query", Toast.LENGTH_SHORT).show()
        }
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedQuery&media=music&entity=song&limit=1"
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    runOnUiThread { openSpotifySearch(query) }
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val body = response.body?.string() ?: ""
                    val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                    val results = json.getAsJsonArray("results")
                    if (results.size() > 0) {
                        val track = results[0].asJsonObject
                        val previewUrl = track.get("previewUrl")?.asString
                        val trackName = track.get("trackName")?.asString ?: query
                        val artistName = track.get("artistName")?.asString ?: ""
                        if (!previewUrl.isNullOrBlank()) {
                            runOnUiThread { playMusicPreview(previewUrl, trackName, artistName) }
                        } else {
                            runOnUiThread { openSpotifySearch(query) }
                        }
                    } else {
                        runOnUiThread { openSpotifySearch(query) }
                    }
                }
            })
        } catch (e: Exception) {
            runOnUiThread { openSpotifySearch(query) }
        }
        return "Searching for music: $query"
    }

    private fun playMusicPreview(url: String, trackName: String, artistName: String) {
        itunesMediaPlayer?.release()
        try {
            itunesMediaPlayer = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(url)
                prepareAsync()
                setOnPreparedListener { mp ->
                    mp.start()
                    binding.cardMusicPlayer.visibility = View.VISIBLE
                    binding.tvMusicTrackName.text = trackName
                    binding.tvMusicArtistName.text = artistName
                    binding.btnMusicPlayPause.text = "Pause"
                    binding.tvMusicDuration.text = formatMs(mp.duration)
                    startMusicProgress(mp)
                }
                setOnCompletionListener {
                    binding.cardMusicPlayer.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Music play failed: ${e.message}")
        }
    }

    private fun startMusicProgress(mp: MediaPlayer) {
        musicProgressHandler.removeCallbacksAndMessages(null)
        val runnable = object : Runnable {
            override fun run() {
                if (mp.isPlaying) {
                    binding.musicSeekBar.max = mp.duration
                    binding.musicSeekBar.progress = mp.currentPosition
                    binding.tvMusicCurrentTime.text = formatMs(mp.currentPosition)
                    musicProgressHandler.postDelayed(this, 500)
                }
            }
        }
        musicProgressHandler.post(runnable)
    }

    private fun formatMs(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun openSpotifySearch(query: String) {
        try {
            val uri = android.net.Uri.parse("spotify:search:$query")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                putExtra(Intent.EXTRA_REFERRER, android.net.Uri.parse("android-app://$packageName"))
            }
            startActivity(intent)
        } catch (e: Exception) {
            val webUri = android.net.Uri.parse("https://open.spotify.com/search/$query")
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }

    private fun handlePlayYoutube(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: args["video"] as? String ?: return "Please specify a video."
        runOnUiThread {
            try {
                val searchUri = android.net.Uri.parse("https://www.youtube.com/results?search_query=${java.net.URLEncoder.encode(query, "UTF-8")}")
                val intent = Intent(Intent.ACTION_VIEW, searchUri)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open YouTube", Toast.LENGTH_SHORT).show()
            }
        }
        return "Opening YouTube for: $query"
    }

    private fun handlePhoneCall(args: Map<String, Any>): String {
        val name = args["name"] as? String ?: return "Please specify who to call."
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Contacts permission required to make calls."
        }
        var number: String? = null
        val cursor = contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use { if (it.moveToFirst()) number = it.getString(0) }

        runOnUiThread {
            if (number != null) {
                val uri = android.net.Uri.parse("tel:$number")
                val intent = if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    Intent(Intent.ACTION_CALL, uri)
                } else {
                    Intent(Intent.ACTION_DIAL, uri)
                }
                startActivity(intent)
            } else {
                startActivity(Intent(Intent.ACTION_DIAL))
                Toast.makeText(this, "Contact '$name' not found", Toast.LENGTH_SHORT).show()
            }
        }
        return if (number != null) "Calling $name." else "Contact not found, opening dial pad."
    }

    private fun handleDirections(args: Map<String, Any>): String {
        val destination = args["destination"] as? String ?: return "Please specify a destination."
        val mode = (args["mode"] as? String ?: "").lowercase()
        runOnUiThread {
            val uri = when {
                "train" in mode -> android.net.Uri.parse("https://www.irctc.co.in")
                "bus" in mode -> android.net.Uri.parse("https://www.redbus.in")
                "flight" in mode || "plane" in mode ->
                    android.net.Uri.parse("https://www.google.com/flights?q=flights+to+${java.net.URLEncoder.encode(destination, "UTF-8")}")
                else ->
                    android.net.Uri.parse("https://maps.google.com/maps?daddr=${java.net.URLEncoder.encode(destination, "UTF-8")}")
            }
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
        return "Opening directions to $destination."
    }

    private fun handleWeather(args: Map<String, Any>): String {
        val location = args["location"] as? String ?: args["city"] as? String ?: return "Please specify a location."
        return try {
            val encoded = java.net.URLEncoder.encode(location, "UTF-8")
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder()
                .url("https://wttr.in/$encoded?format=3")
                .build()
            val response = client.newCall(request).execute()
            response.body?.string()?.trim() ?: "Could not get weather for $location."
        } catch (e: Exception) {
            "Could not retrieve weather: ${e.message}"
        }
    }

    private fun handleWebSearch(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return "Please specify a search query."
        return try {
            kotlinx.coroutines.runBlocking { LocalToolHandlers.webSearchInstant(query) }
        } catch (e: Exception) {
            "Search failed: ${e.message}"
        }
    }

    private fun handleDefineWord(args: Map<String, Any>): String {
        val word = args["word"] as? String ?: return "Please specify a word."
        return try {
            kotlinx.coroutines.runBlocking { LocalToolHandlers.dictionaryLookup(word) }
        } catch (e: Exception) {
            "Definition not found for: $word"
        }
    }

    private fun handleWikiSummary(args: Map<String, Any>): String {
        val topic = args["topic"] as? String ?: return "Please specify a topic."
        return try {
            kotlinx.coroutines.runBlocking { LocalToolHandlers.wikiSummary(topic) }
        } catch (e: Exception) {
            "Could not retrieve Wikipedia summary for: $topic"
        }
    }

    private fun handleStockQuote(args: Map<String, Any>): String {
        val symbol = args["symbol"] as? String ?: args["ticker"] as? String ?: return "Please specify a stock symbol."
        return try {
            kotlinx.coroutines.runBlocking { LocalToolHandlers.stockQuote(symbol) }
        } catch (e: Exception) {
            "Could not retrieve stock price for: $symbol"
        }
    }

    private fun handleMuteAi(): String {
        runOnUiThread { toggleAiMute() }
        return "AI muted."
    }

    private fun handleReadNotifications(): String {
        if (!isNotificationListenerEnabled()) return "Notification access not enabled."
        return try {
            val notifications = com.sdk.glassessdksample.NotificationListener.getRecentNotifications(applicationContext)
            if (notifications.isEmpty()) "No recent notifications."
            else "Recent notifications: ${notifications.take(5).joinToString("; ") { "${it.title}: ${it.text}" }}"
        } catch (e: Exception) {
            "Could not read notifications."
        }
    }

    private fun handleSayGoodbye(): String {
        runOnUiThread { stopConversation() }
        return "Goodbye! Wake me with 'Hey IMI' anytime."
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun isSingleShotMode(): Boolean {
        val prefs = getSharedPreferences("imi_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_VOICE_MODE, VOICE_MODE_SEAMLESS) == VOICE_MODE_SINGLE_SHOT
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        val perms = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        perms.forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                needed.add(it)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }
}
