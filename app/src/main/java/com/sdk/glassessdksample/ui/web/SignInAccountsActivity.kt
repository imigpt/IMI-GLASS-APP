package com.sdk.glassessdksample.ui.web

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.databinding.ActivitySignInAccountsBinding
import com.sdk.glassessdksample.utils.SystemBarsInsets

/**
 * Sign into the sites IMI will act on, before it needs them.
 *
 * The browser agent inherits every session the in-app browser holds, so a site
 * signed into here is already signed in when a task reaches it. Without this
 * the first the user knew of a login wall was the agent stopping halfway
 * through a booking and handing them the phone — this turns that interruption
 * into something they do once, deliberately, at a moment of their choosing.
 *
 * The app never handles credentials: each row opens the site's own sign-in
 * page in [WebBrowserActivity] and the user types into the site itself.
 */
class SignInAccountsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignInAccountsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignInAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)

        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        // Rebuilt on every return: the user has usually just come back from
        // signing into one of these, and the row must reflect that.
        renderRows()
    }

    private fun renderRows() {
        binding.listSites.removeAllViews()
        SignInSite.entries.forEach { site ->
            binding.listSites.addView(buildRow(site))
        }
    }

    private fun buildRow(site: SignInSite): View {
        val signedIn = site.hasSession()

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
            setOnClickListener { openSignIn(site) }
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        textColumn.addView(TextView(this).apply {
            setText(site.displayName)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })

        textColumn.addView(TextView(this).apply {
            // Never a confident "signed in" — all we can see is that cookies
            // exist, not that the site still honours them. Saying "earlier"
            // keeps the claim honest.
            text = if (signedIn) "Signed in earlier · ${site.purpose}" else site.purpose
            setTextColor(Color.parseColor(if (signedIn) "#FF7F2E" else "#ADADAD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(textColumn)

        row.addView(TextView(this).apply {
            text = if (signedIn) "Sign out" else "Sign in"
            setTextColor(Color.parseColor(if (signedIn) "#ADADAD" else "#FF7F2E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener {
                if (signedIn) confirmSignOut(site) else openSignIn(site)
            }
        })

        return row
    }

    /** Opens the site's own sign-in page in the in-app browser. */
    private fun openSignIn(site: SignInSite) {
        startActivity(
            Intent(this, WebBrowserActivity::class.java)
                .putExtra(WebBrowserActivity.EXTRA_URL, site.signInUrl)
        )
    }

    /**
     * Signing out is destructive in a way that is easy to underestimate — it
     * is exactly the session the agent depends on — so it is confirmed.
     */
    private fun confirmSignOut(site: SignInSite) {
        AlertDialog.Builder(this)
            .setTitle("Sign out of ${site.displayName}?")
            .setMessage(
                "IMI will stop being able to do tasks on ${site.displayName} " +
                    "until you sign in again."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign out") { _, _ ->
                site.clearSession()
                renderRows()
            }
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
