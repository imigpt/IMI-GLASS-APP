package com.sdk.glassessdksample

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.databinding.ActivitySettingsBinding
import com.sdk.glassessdksample.ui.Mark1BottomNavManager
import com.sdk.glassessdksample.ui.GeminiLiveService
import com.sdk.glassessdksample.ui.HotHelper
import com.sdk.glassessdksample.ui.ModelProvider
import com.sdk.glassessdksample.ui.UsageLimitManager
import com.sdk.glassessdksample.wakeword.WakeWordEngine
import com.sdk.glassessdksample.wakeword.WakeWordEngineSettings

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSettings()
        Mark1BottomNavManager.setup(this, binding.bottomNavigation, R.id.nav_settings)
    }

    override fun onResume() {
        super.onResume()
        refreshUsageUi()
        updateWakeEngineUi(WakeWordEngineSettings.getSelectedEngine(this))
    }

    private fun setupSettings() {
        val prefs = getSharedPreferences("imi_prefs", MODE_PRIVATE)

        // Continuous Chat toggle
        binding.switchContinuousChat.isChecked = prefs.getBoolean("continuous_chat", true)
        binding.switchContinuousChat.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("continuous_chat", isChecked).apply()
        }

        // Hidden model/wake engine buttons still wired so existing logic works
        val currentModel = GeminiLiveService.getSavedModelProvider(this)
        updateModelUi(currentModel)

        val currentWakeEngine = WakeWordEngineSettings.getSelectedEngine(this)
        updateWakeEngineUi(currentWakeEngine)

        binding.btnModelGpt.setOnClickListener { setModel(ModelProvider.GPT_REALTIME) }
        binding.btnModelGemini.setOnClickListener { setModel(ModelProvider.GEMINI_LIVE) }
        binding.btnWakeEngineCustom.setOnClickListener { setWakeWordEngine(WakeWordEngine.CUSTOM_ONNX) }
        binding.btnWakeEngineSnowboy.setOnClickListener { setWakeWordEngine(WakeWordEngine.SNOWBOY) }

        binding.switchGeminiAck.isChecked = prefs.getBoolean("useGeminiAck", true)
        binding.switchGeminiAck.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("useGeminiAck", isChecked).apply()
        }

        binding.btnOpenPermissionSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        binding.btnUpgradePlan.setOnClickListener {
            UsageLimitManager.showUpgradeDialog(this) {
                refreshUsageUi()
            }
        }

        refreshUsageUi()
    }

    private fun setWakeWordEngine(engine: WakeWordEngine) {
        val current = WakeWordEngineSettings.getSelectedEngine(this)
        if (current == engine) return

        WakeWordEngineSettings.setSelectedEngine(this, engine)
        try {
            HotHelper.getInstance(applicationContext).setWakeWordEngine(engine)
        } catch (_: Exception) {
            // HotHelper may not be active yet; preference is already persisted.
        }
        updateWakeEngineUi(engine)

        Toast.makeText(this, "Wake engine set to ${engine.displayName}", Toast.LENGTH_SHORT).show()
    }

    private fun setModel(provider: ModelProvider) {
        val current = GeminiLiveService.getSavedModelProvider(this)
        if (current == provider) return

        GeminiLiveService.saveModelProvider(this, provider)
        updateModelUi(provider)

        val name = if (provider == ModelProvider.GPT_REALTIME) "GPT" else "Gemini"
        Toast.makeText(this, "AI model switched to $name", Toast.LENGTH_SHORT).show()
    }

    private fun updateModelUi(provider: ModelProvider) {
        if (provider == ModelProvider.GPT_REALTIME) {
            binding.btnModelGpt.isEnabled = false
            binding.btnModelGemini.isEnabled = true
            binding.tvCurrentModel.text = "Current model: GPT"
        } else {
            binding.btnModelGpt.isEnabled = true
            binding.btnModelGemini.isEnabled = false
            binding.tvCurrentModel.text = "Current model: Gemini"
        }
    }

    private fun updateWakeEngineUi(engine: WakeWordEngine) {
        binding.btnWakeEngineCustom.isEnabled = engine != WakeWordEngine.CUSTOM_ONNX
        binding.btnWakeEngineSnowboy.isEnabled = engine != WakeWordEngine.SNOWBOY
        binding.tvCurrentWakeEngine.text = "Current engine: ${engine.displayName}"
    }

    private fun refreshUsageUi() {
        val snapshot = UsageLimitManager.getSnapshot(this)

        binding.tvCurrentPlan.text = snapshot.plan.title
        binding.tvPlanPrice.text = "${snapshot.plan.title} – ${snapshot.plan.priceLabel}/month"

        bindUsage(
            usage = snapshot.voice,
            callsView = binding.tvVoiceCalls,
            progressView = binding.progressVoiceCalls,
            remainingView = binding.tvVoiceRemaining
        )

        bindUsage(
            usage = snapshot.seeing,
            callsView = binding.tvSeeingCalls,
            progressView = binding.progressSeeingCalls,
            remainingView = binding.tvSeeingRemaining
        )

        bindUsage(
            usage = snapshot.chat,
            callsView = binding.tvChatCalls,
            progressView = binding.progressChatCalls,
            remainingView = binding.tvChatRemaining
        )
    }

    private fun bindUsage(
        usage: UsageLimitManager.FeatureUsage,
        callsView: TextView,
        progressView: ProgressBar,
        remainingView: TextView
    ) {
        callsView.text = "${usage.used} / ${usage.limit}"
        progressView.progress = usage.progressPercent
        remainingView.text = "Remaining : ${usage.remaining}"
    }
}
