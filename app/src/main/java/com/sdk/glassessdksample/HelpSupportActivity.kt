package com.sdk.glassessdksample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sdk.glassessdksample.auth.SessionManager
import com.sdk.glassessdksample.databinding.ActivityHelpSupportBinding
import com.sdk.glassessdksample.databinding.ItemFaqBinding
import com.sdk.glassessdksample.ui.sync.SupportTicketApi
import com.sdk.glassessdksample.utils.SystemBarsInsets
import java.util.concurrent.Executors

class HelpSupportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpSupportBinding

    private val supportEmail = "tanay@imiglasses.com"

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private data class Faq(val question: String, val answer: String)

    private val faqs = listOf(
        Faq(
            "How do I connect my IMI Glasses?",
            "Open Profile > Switch Device to choose Mark 1 or Mark 2, then follow the pairing steps. Make sure Bluetooth is enabled and the glasses are charged."
        ),
        Faq(
            "How do I change the wake word or AI model?",
            "Go to Profile > Settings to switch between the available AI models and wake word engines."
        ),
        Faq(
            "Why isn't the assistant responding?",
            "Check that the glasses are connected, your phone has an active internet connection, and microphone permission is granted under Profile > Permission."
        ),
        Faq(
            "How do I upgrade my plan?",
            "Open Profile and tap Upgrade on the plan card, or from Settings tap Upgrade Plan to see available options."
        ),
        Faq(
            "How do I clear my data?",
            "Go to Profile > Manage Data > Clear Data. This removes saved preferences, conversation history, and settings from this device."
        ),
        Faq(
            "How do I contact support?",
            "Tap Email Support above to send us a message, or use the Raise a Complaint box to describe your issue directly."
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHelpSupportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)

        binding.backButton.setOnClickListener { finish() }
        binding.tvSupportEmail.text = supportEmail
        binding.versionText.text = "v${getVersionName()}"

        setupFaqList()
        setupActions()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun setupActions() {
        binding.emailSupportButton.setOnClickListener { sendSupportEmail() }

        binding.btnSubmitComplaint.setOnClickListener { submitComplaint() }

        binding.rateUsButton.setOnClickListener { showRateUsDialog() }

        binding.privacyPolicyButton.setOnClickListener {
            openUrl("https://www.imiglasses.com/privacy-policy")
        }

        binding.aboutUsButton.setOnClickListener {
            openUrl("https://www.imiglasses.com/about-us")
        }
    }

    private fun setupFaqList() {
        binding.faqContainer.removeAllViews()
        faqs.forEach { faq ->
            val itemBinding = ItemFaqBinding.inflate(LayoutInflater.from(this), binding.faqContainer, false)
            itemBinding.faqQuestion.text = faq.question
            itemBinding.faqAnswer.text = faq.answer

            itemBinding.faqHeader.setOnClickListener {
                val expanded = itemBinding.faqAnswer.visibility == View.VISIBLE
                itemBinding.faqAnswer.visibility = if (expanded) View.GONE else View.VISIBLE
                itemBinding.faqChevron.rotation = if (expanded) 0f else 180f
            }

            binding.faqContainer.addView(itemBinding.root)
        }
    }

    private fun sendSupportEmail() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(supportEmail))
            putExtra(Intent.EXTRA_SUBJECT, "IMI Glasses App Support - v${getVersionName()}")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Files the complaint as a support ticket (POST /v1/support/tickets) when
     * the user is signed in, so it lands straight in the admin queue tagged
     * with their account. Falls back to a plain email draft when signed out,
     * since the ticket endpoint requires an access token and there is no
     * anonymous ticket path.
     */
    private fun submitComplaint() {
        val complaint = binding.etComplaint.text.toString().trim()
        if (complaint.isEmpty()) {
            binding.etComplaint.error = "Please describe your issue"
            return
        }
        if (complaint.length > SupportTicketApi.MAX_MESSAGE_CHARS) {
            binding.etComplaint.error = "Please keep this under ${SupportTicketApi.MAX_MESSAGE_CHARS} characters"
            return
        }

        if (!SessionManager(this).isLoggedIn) {
            sendComplaintEmail(complaint)
            return
        }

        setSubmitting(true)
        io.execute {
            val result = SupportTicketApi(this).create(complaint)
            mainHandler.post {
                setSubmitting(false)
                when (result) {
                    is SupportTicketApi.Result.Ok -> {
                        binding.etComplaint.text.clear()
                        Toast.makeText(this, "Thanks — we've received your complaint", Toast.LENGTH_SHORT).show()
                    }
                    is SupportTicketApi.Result.Err -> {
                        if (result.auth) {
                            sendComplaintEmail(complaint)
                        } else {
                            Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun setSubmitting(submitting: Boolean) {
        binding.btnSubmitComplaint.isEnabled = !submitting
        binding.btnSubmitComplaint.text = if (submitting) "Submitting..." else "Submit"
    }

    private fun sendComplaintEmail(complaint: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(supportEmail))
            putExtra(Intent.EXTRA_SUBJECT, "IMI Glasses App Complaint - v${getVersionName()}")
            putExtra(Intent.EXTRA_TEXT, complaint)
        }
        try {
            startActivity(intent)
            binding.etComplaint.text.clear()
        } catch (e: Exception) {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRateUsDialog() {
        val sheet = BottomSheetDialog(this, R.style.BottomSheetStyle)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_rate_us, null)
        sheet.setContentView(view)

        view.findViewById<ImageView>(R.id.btnCloseRateUs).setOnClickListener {
            sheet.dismiss()
        }

        val stars = listOf(
            view.findViewById<ImageView>(R.id.star1),
            view.findViewById<ImageView>(R.id.star2),
            view.findViewById<ImageView>(R.id.star3),
            view.findViewById<ImageView>(R.id.star4),
            view.findViewById<ImageView>(R.id.star5)
        )
        stars.forEach { star ->
            star.setOnClickListener {
                sheet.dismiss()
                openPlayStore()
            }
        }

        sheet.show()
    }

    private fun openPlayStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        } catch (e: Exception) {
            Toast.makeText(this, "Play Store not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun getVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
