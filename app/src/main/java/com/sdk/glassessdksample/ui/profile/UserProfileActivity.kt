package com.sdk.glassessdksample.ui.profile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.ListeningService
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.databinding.ActivitySignInAccountsBinding
import com.sdk.glassessdksample.ui.HotHelper
import com.sdk.glassessdksample.utils.SystemBarsInsets
import java.text.DateFormat
import java.util.Date

/**
 * "What IMI knows about you" — the profile imported from the user's own AI.
 *
 * Shows the current profile if there is one, and offers to import from ChatGPT
 * or Claude. Everything the assistant has been told about the user is visible
 * here, editable, and deletable: a profile the user cannot inspect would be a
 * far worse deal than one they assembled themselves.
 *
 * Reuses the Signed-in sites layout, which is the same shape — a header and a
 * list of service rows.
 */
class UserProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignInAccountsBinding
    private lateinit var store: UserProfileStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignInAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)

        store = UserProfileStore(this)
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        // Silence the wake word for this whole section, not only the import.
        //
        // Re-engaged in onResume rather than onCreate because returning from
        // the import screen runs that screen's onDestroy, which RELEASES
        // suppression — without re-engaging here, coming back from an import
        // would quietly re-arm the mic on a screen that is meant to be silent.
        suppressWakeWord()

        // Rebuilt every time: the user usually arrives back here straight from
        // an import, and the screen must show what they just saved.
        render()
    }

    /**
     * Re-engages suppression once this screen actually has focus.
     *
     * Returning from the import screen interleaves as: this onResume, then the
     * import screen's onDestroy — which releases suppression, undoing what
     * onResume just set and re-arming the mic here. Window focus arrives after
     * that teardown, so re-engaging here is what actually sticks.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) suppressWakeWord()
    }

    override fun onPause() {
        super.onPause()
        // Leaving this section entirely hands the wake word back. Navigating
        // INTO the import screen also passes through here, and that screen
        // engages its own suppression in onCreate — which runs before this, so
        // the mic never gets a window in which it is live.
        if (!isChangingConfigurations) releaseWakeWord()
    }

    /**
     * Stops wake-word detection while the user is in this section.
     *
     * Everything here is a screen the user is reading or typing a password
     * into, and a wake word firing starts a voice session that brings the home
     * screen to the front — destroying whatever they were part-way through.
     *
     * [ListeningService] is stopped as well as the in-process detector: that
     * service owns the background detector which keeps running when the app is
     * not foregrounded, so suppressing only the local one leaves it free to
     * fire and navigate away.
     */
    private fun suppressWakeWord() {
        try {
            HotHelper.getInstance(applicationContext).apply {
                setSuppressed(true)
                stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not suppress wake word: ${e.message}")
        }
        try {
            startService(
                Intent(this, ListeningService::class.java)
                    .apply { action = ListeningService.ACTION_STOP }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop ListeningService: ${e.message}")
        }
    }

    private fun releaseWakeWord() {
        try {
            HotHelper.getInstance(applicationContext).setSuppressed(false)
        } catch (e: Exception) {
            Log.w(TAG, "Could not release wake-word suppression: ${e.message}")
        }
    }

    private fun render() {
        binding.listSites.removeAllViews()

        val profile = store.load()
        binding.listSites.addView(headerText(
            if (profile == null) {
                "IMI doesn't know anything about you yet. Import your profile from " +
                    "ChatGPT or Claude and it can skip the questions you've already " +
                    "answered there."
            } else {
                "This is what IMI knows about you. It's stored only on this phone."
            }
        ))

        if (profile != null) binding.listSites.addView(profileCard(profile))

        ProfileSource.entries.forEach { source ->
            binding.listSites.addView(sourceRow(source, profile))
        }
    }

    private fun headerText(text: String) = TextView(this).apply {
        setText(text)
        setTextColor(Color.parseColor("#CCFFFFFF"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(2), 0, dp(2), dp(14))
    }

    /** The profile itself, with the controls that make it the user's to manage. */
    private fun profileCard(profile: UserProfileStore.Profile): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_more_quick_note)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        card.addView(TextView(this).apply {
            text = "From ${profile.source.displayName} · " +
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(profile.importedAt))
            setTextColor(Color.parseColor("#FF7F2E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(8))
        })

        card.addView(TextView(this).apply {
            text = profile.body
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        card.addView(TextView(this).apply {
            text = "Delete what IMI knows"
            setTextColor(Color.parseColor("#FF6B6B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(12), 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { confirmDelete() }
        })

        return card
    }

    private fun sourceRow(source: ProfileSource, current: UserProfileStore.Profile?): View {
        val isCurrent = current?.source == source

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_more_quick_note)
            setPadding(dp(14), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) }
            isClickable = true
            isFocusable = true
            setOnClickListener { startImport(source) }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        column.addView(TextView(this).apply {
            text = source.displayName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })

        column.addView(TextView(this).apply {
            // States the whole bargain in one line, because this is the moment
            // the user decides whether to hand over access at all.
            text = "Sign in, import, sign out — IMI never keeps your login"
            setTextColor(Color.parseColor("#ADADAD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(column)

        row.addView(TextView(this).apply {
            text = if (isCurrent) "Refresh" else "Import"
            setTextColor(Color.parseColor("#FF7F2E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { startImport(source) }
        })

        return row
    }

    private fun startImport(source: ProfileSource) {
        startActivity(ProfileImportActivity.intent(this, source))
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete your profile?")
            .setMessage(
                "IMI will forget everything it learned about you. You can import " +
                    "it again at any time."
            )
            .setPositiveButton("Delete") { _, _ ->
                store.clear()
                render()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "UserProfile"
    }
}
