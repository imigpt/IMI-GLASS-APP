package com.sdk.glassessdksample.ui.profile

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sdk.glassessdksample.ListeningService
import com.sdk.glassessdksample.databinding.ActivityProfileImportBinding
import com.sdk.glassessdksample.ui.HotHelper
import com.sdk.glassessdksample.utils.SystemBarsInsets
import com.sdk.glassessdksample.ui.web.PopupWindowRouter
import com.sdk.glassessdksample.ui.web.WebSessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Imports what the user's own AI knows about them, with their consent at every
 * step.
 *
 * The flow, and why it is shaped this way:
 *
 * 1. **The user signs in themselves** in the WebView below. Credentials go to
 *    the site, never through this app — same principle as the browser agent.
 * 2. **The import runs** with the WebView hidden: one fixed prompt in a fresh
 *    chat. Hidden rather than secret — the status line says what is happening,
 *    and the user pressed the button that started it.
 * 3. **The user reads and edits the result** before anything is stored. What
 *    they approve is what gets saved, which makes the profile both consented to
 *    and more accurate than the raw answer.
 * 4. **The account is signed out**, always, including when the import failed.
 *
 * Step 4 is the promise this screen makes: the account is borrowed for one
 * question, not held. [finishAndSignOut] is the only exit, so there is no path
 * that leaves a live session behind.
 */
class ProfileImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileImportBinding
    private lateinit var source: ProfileSource
    private lateinit var importer: ProfileImporter
    private lateinit var store: UserProfileStore

    private var importJob: Job? = null
    private var stage = Stage.SIGN_IN

    private enum class Stage { SIGN_IN, IMPORTING, REVIEW }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)

        source = intent.getStringExtra(EXTRA_SOURCE)
            ?.let { name -> ProfileSource.entries.firstOrNull { it.name == name } }
            ?: ProfileSource.CHATGPT

        importer = ProfileImporter(this)
        store = UserProfileStore(this)

        binding.tvTitle.text = "Import from ${source.displayName}"

        WebSessionManager.configure(binding.webView, desktopMode = false)
        // Report what the page is actually doing. A WebView that fails to load
        // shows the same blank rectangle whatever the cause, so without this
        // every failure looks identical from the screen and has to be
        // diagnosed by attaching devtools.
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page finished: $url")
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val reason = error?.description ?: "unknown"
                    Log.w(TAG, "Page load failed: ${request.url} -> $reason")
                    runOnUiThread {
                        binding.tvStatus.text =
                            "Couldn't load ${source.displayName}: $reason. " +
                                "Check your connection and try again."
                    }
                }
            }
        }

        // "Continue with Google" opens its flow in a JS popup (window.open),
        // not a normal navigation. Without an onCreateWindow handler the
        // WebView silently drops that request and is left sitting on
        // about:blank — which, with the opaque background, is a plain white
        // screen that looks like the login page failed to load. Routing the
        // popup back into this same WebView is what makes OAuth sign-in work.
        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = PopupWindowRouter.routeInto(binding.webView, resultMsg)
        }

        binding.btnBack.setOnClickListener { confirmExit() }
        binding.btnCancel.setOnClickListener { confirmExit() }
        binding.btnPrimary.setOnClickListener { onPrimary() }

        suppressWakeWord()
        showSignIn()
    }

    /**
     * Silences the wake word for as long as this screen is open.
     *
     * A wake word firing mid-import is not a small annoyance: it starts a voice
     * session, which brings the home screen to the front, which tears this
     * Activity down — losing the sign-in and the half-finished import. The user
     * is typing a password and reading a profile here, so there is nothing they
     * could want the wake word for anyway.
     *
     * [ListeningService] is stopped as well as the detector: it owns the
     * background detector that keeps running when the app is not foregrounded,
     * and suppressing only the in-process one leaves that free to fire.
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

    /**
     * Hands the wake word back.
     *
     * Deliberately does not restart the detector, matching how meeting and
     * vision suppression are released: the home screen re-arms it in onResume,
     * and starting it from here would race that.
     */
    private fun releaseWakeWord() {
        try {
            HotHelper.getInstance(applicationContext).setSuppressed(false)
        } catch (e: Exception) {
            Log.w(TAG, "Could not release wake-word suppression: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ stages

    private fun showSignIn() {
        stage = Stage.SIGN_IN
        binding.tvStatus.text = if (ProfileImporter.SKIP_SIGN_OUT) {
            "Sign in to ${source.displayName}. IMI will then ask it what it " +
                "remembers about you and show you the answer."
        } else {
            "Sign in to ${source.displayName}. IMI will then ask it what it " +
                "remembers about you, show you the answer, and sign you back out."
        }
        binding.webView.visibility = View.VISIBLE
        binding.reviewScroll.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.btnPrimary.text = "I'm signed in"
        binding.webView.loadUrl(source.signInUrl)
    }

    private fun startImport() {
        stage = Stage.IMPORTING
        // Hidden while it works: the page is mid-automation and showing it
        // would look like the app doing something the user did not ask for.
        // The status line keeps them informed instead.
        binding.webView.visibility = View.INVISIBLE
        binding.progress.visibility = View.VISIBLE
        binding.btnPrimary.visibility = View.GONE

        importJob = lifecycleScope.launch {
            val result = importer.import(binding.webView, source) { message ->
                runOnUiThread { binding.tvStatus.text = message }
            }
            binding.progress.visibility = View.GONE
            binding.btnPrimary.visibility = View.VISIBLE

            when (result) {
                is ProfileImporter.Result.Success -> showReview(result.text)

                ProfileImporter.Result.NoMemory -> {
                    // A real and fairly common outcome: memory off, or a new
                    // account. Saying so plainly beats showing an empty box the
                    // user cannot make sense of.
                    binding.tvStatus.text =
                        "${source.displayName} doesn't have any saved memories of you " +
                            "yet, so there's nothing to import. If you'd like this to " +
                            "work, turn on memory in ${source.displayName} and try again."
                    binding.btnPrimary.text = "Done"
                    stage = Stage.REVIEW
                }

                ProfileImporter.Result.NotSignedIn -> {
                    binding.tvStatus.text =
                        "You're not signed in yet. Sign in below, then tap again."
                    binding.webView.visibility = View.VISIBLE
                    binding.btnPrimary.text = "I'm signed in"
                    stage = Stage.SIGN_IN
                }

                is ProfileImporter.Result.Failed -> {
                    binding.tvStatus.text = result.reason
                    binding.btnPrimary.text = "Close"
                    stage = Stage.REVIEW
                }
            }
        }
    }

    private fun showReview(text: String) {
        stage = Stage.REVIEW
        binding.tvStatus.text =
            "Imported from ${source.displayName}. Nothing is saved until you tap Save."
        binding.webView.visibility = View.GONE
        binding.reviewScroll.visibility = View.VISIBLE
        binding.etProfile.setText(text)
        binding.btnPrimary.text = "Save"
    }

    // ----------------------------------------------------------------- actions

    private fun onPrimary() {
        when (stage) {
            Stage.SIGN_IN -> startImport()
            Stage.IMPORTING -> Unit
            Stage.REVIEW -> {
                val edited = binding.etProfile.text?.toString().orEmpty().trim()
                if (binding.reviewScroll.visibility == View.VISIBLE && edited.isNotBlank()) {
                    store.save(edited, source)
                    Toast.makeText(
                        this,
                        "Saved. IMI will use this when you talk to it.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                finishAndSignOut()
            }
        }
    }

    private fun confirmExit() {
        if (stage == Stage.IMPORTING) {
            AlertDialog.Builder(this)
                .setTitle("Stop importing?")
                .setMessage("You'll be signed out of ${source.displayName} either way.")
                .setPositiveButton("Stop") { _, _ -> finishAndSignOut() }
                .setNegativeButton("Keep going", null)
                .show()
            return
        }
        finishAndSignOut()
    }

    /**
     * The only way out of this screen.
     *
     * Sign-out is shown rather than done quietly: it is the promise the screen
     * made, and a promise the user cannot see is one they have to take on
     * trust. It also runs on every exit path — cancelled, failed or saved — so
     * a broken import cannot leave the account logged in.
     */
    private fun finishAndSignOut() {
        importJob?.cancel()
        binding.reviewScroll.visibility = View.GONE
        binding.webView.visibility = View.GONE
        binding.progress.visibility = View.VISIBLE
        binding.btnPrimary.visibility = View.GONE
        binding.btnCancel.visibility = View.GONE
        // Wording follows what actually happens: with sign-out disabled for
        // debugging, claiming "Signed out." would be a promise the code is not
        // currently keeping.
        binding.tvStatus.text = if (ProfileImporter.SKIP_SIGN_OUT) {
            "Finishing…"
        } else {
            "Signing you out of ${source.displayName}…"
        }

        lifecycleScope.launch {
            importer.signOut(binding.webView, source)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // In onDestroy rather than beside finish(): this runs however the
        // screen goes away, including a system kill, so the wake word can
        // never be left permanently suppressed.
        //
        // Releasing unconditionally is safe even though UserProfileActivity
        // suppresses for its own screen: Android runs that screen's onResume
        // before this onDestroy, but it re-engages suppression there on EVERY
        // resume, so a release here cannot leave the mic live on it.
        releaseWakeWord()
        importJob?.cancel()
        try {
            binding.webView.stopLoading()
            binding.webView.destroy()
        } catch (_: Exception) {
            // Teardown only; nothing useful to do if the view is already gone.
        }
    }

    companion object {
        private const val TAG = "ProfileImport"
        private const val EXTRA_SOURCE = "source"

        fun intent(context: Context, source: ProfileSource): Intent =
            Intent(context, ProfileImportActivity::class.java)
                .putExtra(EXTRA_SOURCE, source.name)
    }
}
