package com.sdk.glassessdksample.ui

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sdk.glassessdksample.R
import kotlin.math.max
import kotlin.math.min

/**
 * Tracks per-feature usage limits and plan upgrades.
 */
object UsageLimitManager {

    private const val PREFS_NAME = "imi_usage_limits"
    private const val KEY_PLAN = "current_plan"
    private const val KEY_USED_CHAT = "used_chat_calls"
    private const val KEY_USED_SEEING = "used_seeing_calls"
    private const val KEY_USED_VOICE = "used_voice_calls"

    data class Limits(
        val voiceCalls: Int,
        val seeingCalls: Int,
        val chatCalls: Int
    )

    data class FeatureUsage(
        val used: Int,
        val limit: Int,
        val remaining: Int,
        val progressPercent: Int
    )

    data class UsageSnapshot(
        val plan: Plan,
        val voice: FeatureUsage,
        val seeing: FeatureUsage,
        val chat: FeatureUsage
    )

    enum class Plan(
        val key: String,
        val title: String,
        val priceLabel: String,
        val limits: Limits,
        val subtitle: String
    ) {
        FREE(
            key = "free",
            title = "Free",
            priceLabel = "₹0",
            limits = Limits(
                voiceCalls = 15,
                seeingCalls = 5,
                chatCalls = 50
            ),
            subtitle = "Starter usage limits"
        ),
        PRO(
            key = "pro",
            title = "Pro Plan",
            priceLabel = "₹500",
            limits = Limits(
                voiceCalls = 120,
                seeingCalls = 30,
                chatCalls = 300
            ),
            subtitle = "Increased usage limits"
        ),
        ULTRA(
            key = "ultra",
            title = "Ultra Plan",
            priceLabel = "₹3000",
            limits = Limits(
                voiceCalls = 1000,
                seeingCalls = 250,
                chatCalls = 2500
            ),
            subtitle = "Higher usage limits"
        );

        companion object {
            fun fromKey(key: String?): Plan {
                return entries.firstOrNull { it.key == key } ?: FREE
            }
        }
    }

    @Synchronized
    fun tryConsume(context: Context?, mode: TokenUsageTracker.Mode): Boolean {
        if (context == null) return true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val used = getUsedInternal(prefs, mode)
        val limit = getLimit(context, mode)
        if (used >= limit) return false

        setUsedInternal(prefs, mode, used + 1)
        return true
    }

    fun getLimit(context: Context, mode: TokenUsageTracker.Mode): Int {
        val plan = getCurrentPlan(context)
        return when (mode) {
            TokenUsageTracker.Mode.AI_CHAT -> plan.limits.chatCalls
            TokenUsageTracker.Mode.SEEING -> plan.limits.seeingCalls
            TokenUsageTracker.Mode.VOICE_CHAT -> plan.limits.voiceCalls
        }
    }

    fun getUsed(context: Context, mode: TokenUsageTracker.Mode): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return getUsedInternal(prefs, mode)
    }

    fun getRemaining(context: Context, mode: TokenUsageTracker.Mode): Int {
        return max(0, getLimit(context, mode) - getUsed(context, mode))
    }

    fun getSnapshot(context: Context): UsageSnapshot {
        val plan = getCurrentPlan(context)
        return UsageSnapshot(
            plan = plan,
            voice = buildFeatureUsage(context, TokenUsageTracker.Mode.VOICE_CHAT),
            seeing = buildFeatureUsage(context, TokenUsageTracker.Mode.SEEING),
            chat = buildFeatureUsage(context, TokenUsageTracker.Mode.AI_CHAT)
        )
    }

    @Synchronized
    fun setPlan(context: Context, plan: Plan) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLAN, plan.key)
            .apply()
    }

    fun getCurrentPlan(context: Context): Plan {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Plan.fromKey(prefs.getString(KEY_PLAN, Plan.FREE.key))
    }

    fun limitReachedMessage(mode: TokenUsageTracker.Mode): String {
        val feature = when (mode) {
            TokenUsageTracker.Mode.AI_CHAT -> "Chat"
            TokenUsageTracker.Mode.SEEING -> "Vision"
            TokenUsageTracker.Mode.VOICE_CHAT -> "Voice Chat"
        }
        return "$feature usage limit reached. Please upgrade your plan."
    }

    fun promptUpgradeIfPossible(context: Context?, mode: TokenUsageTracker.Mode? = null) {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        activity.runOnUiThread {
            showUpgradeDialog(activity, mode = mode)
        }
    }

    fun showUpgradeDialog(
        activity: Activity,
        mode: TokenUsageTracker.Mode? = null,
        onPlanChanged: ((Plan) -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val current = getCurrentPlan(activity)
        val featureLabel = when (mode) {
            TokenUsageTracker.Mode.AI_CHAT -> "Chat"
            TokenUsageTracker.Mode.SEEING -> "Vision"
            TokenUsageTracker.Mode.VOICE_CHAT -> "Voice Chat"
            null -> ""
        }
        val message = if (featureLabel.isBlank()) {
            "Unlock higher limits and continue using all AI features."
        } else {
            "$featureLabel limit reached. Upgrade to continue."
        }

        val sheet = BottomSheetDialog(activity, R.style.BottomSheetStyle)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_upgrade_plan, null)
        sheet.setContentView(view)
        sheet.setCancelable(true)

        view.findViewById<TextView>(R.id.tvUpgradeSubtitle).text = message
        view.findViewById<TextView>(R.id.tvCurrentPlan).text = "Current plan: ${current.title} (${current.priceLabel})"
        view.findViewById<TextView>(R.id.tvUpgradeHint).text = if (current == Plan.ULTRA) {
            "You are already on the highest plan."
        } else {
            "Upgrade now to unlock higher usage limits."
        }

        bindPlanChoice(
            root = view,
            activity = activity,
            sheet = sheet,
            currentPlan = current,
            targetPlan = Plan.PRO,
            containerId = R.id.layoutProPlan,
            titleId = R.id.tvProTitle,
            priceId = R.id.tvProPrice,
            limitsId = R.id.tvProLimits,
            actionButtonId = R.id.btnSelectPro,
            onPlanChanged = onPlanChanged
        )

        bindPlanChoice(
            root = view,
            activity = activity,
            sheet = sheet,
            currentPlan = current,
            targetPlan = Plan.ULTRA,
            containerId = R.id.layoutUltraPlan,
            titleId = R.id.tvUltraTitle,
            priceId = R.id.tvUltraPrice,
            limitsId = R.id.tvUltraLimits,
            actionButtonId = R.id.btnSelectUltra,
            onPlanChanged = onPlanChanged
        )

        view.findViewById<Button>(R.id.btnKeepCurrent).setOnClickListener {
            sheet.dismiss()
        }

        sheet.show()
    }

    private fun bindPlanChoice(
        root: View,
        activity: Activity,
        sheet: BottomSheetDialog,
        currentPlan: Plan,
        targetPlan: Plan,
        containerId: Int,
        titleId: Int,
        priceId: Int,
        limitsId: Int,
        actionButtonId: Int,
        onPlanChanged: ((Plan) -> Unit)?
    ) {
        val container = root.findViewById<LinearLayout>(containerId)
        val titleView = root.findViewById<TextView>(titleId)
        val priceView = root.findViewById<TextView>(priceId)
        val limitsView = root.findViewById<TextView>(limitsId)
        val actionButton = root.findViewById<Button>(actionButtonId)

        titleView.text = targetPlan.title
        priceView.text = targetPlan.priceLabel
        limitsView.text = "Voice ${targetPlan.limits.voiceCalls} | Vision ${targetPlan.limits.seeingCalls} | Chat ${targetPlan.limits.chatCalls}"

        val isCurrent = currentPlan == targetPlan
        val canUpgradeToPlan = when (currentPlan) {
            Plan.FREE -> true
            Plan.PRO -> targetPlan == Plan.ULTRA
            Plan.ULTRA -> false
        }

        when {
            isCurrent -> {
                container.setBackgroundResource(R.drawable.bg_upgrade_plan_option_active)
                actionButton.setBackgroundResource(R.drawable.bg_upgrade_secondary_button)
                actionButton.text = "Current plan"
                actionButton.isEnabled = false
                actionButton.alpha = 0.9f
                container.setOnClickListener(null)
            }
            canUpgradeToPlan -> {
                val upgradeAction = {
                    setPlan(activity, targetPlan)
                    onPlanChanged?.invoke(targetPlan)
                    Toast.makeText(activity, "${targetPlan.title} activated", Toast.LENGTH_SHORT).show()
                    sheet.dismiss()
                }

                container.alpha = 1f
                container.setBackgroundResource(R.drawable.bg_upgrade_plan_option)
                actionButton.setBackgroundResource(R.drawable.bg_upgrade_action_button)
                actionButton.text = "Upgrade"
                actionButton.isEnabled = true
                actionButton.alpha = 1f
                actionButton.setOnClickListener { upgradeAction() }
                container.setOnClickListener { upgradeAction() }
            }
            else -> {
                container.alpha = 0.68f
                container.setBackgroundResource(R.drawable.bg_upgrade_plan_option)
                actionButton.setBackgroundResource(R.drawable.bg_upgrade_secondary_button)
                actionButton.text = "Not available"
                actionButton.isEnabled = false
                actionButton.alpha = 0.85f
                actionButton.setOnClickListener(null)
                container.setOnClickListener(null)
            }
        }
    }

    private fun buildFeatureUsage(context: Context, mode: TokenUsageTracker.Mode): FeatureUsage {
        val used = getUsed(context, mode)
        val limit = getLimit(context, mode)
        val remaining = max(0, limit - used)
        val progress = if (limit <= 0) 0 else min(100, (used * 100) / limit)
        return FeatureUsage(
            used = used,
            limit = limit,
            remaining = remaining,
            progressPercent = progress
        )
    }

    private fun getUsedInternal(prefs: android.content.SharedPreferences, mode: TokenUsageTracker.Mode): Int {
        val key = when (mode) {
            TokenUsageTracker.Mode.AI_CHAT -> KEY_USED_CHAT
            TokenUsageTracker.Mode.SEEING -> KEY_USED_SEEING
            TokenUsageTracker.Mode.VOICE_CHAT -> KEY_USED_VOICE
        }
        return prefs.getInt(key, 0)
    }

    private fun setUsedInternal(
        prefs: android.content.SharedPreferences,
        mode: TokenUsageTracker.Mode,
        value: Int
    ) {
        val key = when (mode) {
            TokenUsageTracker.Mode.AI_CHAT -> KEY_USED_CHAT
            TokenUsageTracker.Mode.SEEING -> KEY_USED_SEEING
            TokenUsageTracker.Mode.VOICE_CHAT -> KEY_USED_VOICE
        }
        prefs.edit().putInt(key, value).apply()
    }
}
