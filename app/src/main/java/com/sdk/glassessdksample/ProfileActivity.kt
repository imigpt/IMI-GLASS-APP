package com.sdk.glassessdksample

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.sdk.glassessdksample.databinding.ActivityProfileBinding
import com.sdk.glassessdksample.ui.BottomNavManager
import com.sdk.glassessdksample.ui.UsageLimitManager
import com.sdk.glassessdksample.ui.UserMemoryActivity

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var sharedPref: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPref = getSharedPreferences("user_prefs", MODE_PRIVATE)

        setupUI()
        loadUserData()
        refreshUpgradeCard()
    }

    override fun onResume() {
        super.onResume()
        refreshUpgradeCard()
    }

    private fun setupUI() {
        BottomNavManager.setup(binding.bottomNavigation, R.id.nav_profile, this)

        // Back button
        binding.backButton.setOnClickListener {
            finish()
        }

        // Edit profile button
        binding.editProfileButton.setOnClickListener {
            // Open user memory activity for editing
            startActivity(Intent(this, UserMemoryActivity::class.java))
        }

        // Manage Data button
        binding.manageDataButton.setOnClickListener {
            showManageDataDialog()
        }

        // Permission button
        binding.permissionButton.setOnClickListener {
            // Open app settings
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        }

        // Dark Mode toggle
        binding.darkModeToggle.setOnCheckedChangeListener { _, isChecked ->
            sharedPref.edit().putBoolean("dark_mode", isChecked).apply()
            // In a real app, you'd apply the theme change here
        }

        // Rate Us button
        binding.rateUsButton.setOnClickListener {
            openPlayStore()
        }

        // Logout button
        binding.logoutButton.setOnClickListener {
            showLogoutConfirmation()
        }

        // Support link
        binding.supportLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.imi.glass/support"))
            startActivity(intent)
        }

        // Privacy Policy link
        binding.privacyLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.imi.glass/privacy"))
            startActivity(intent)
        }

        // About Us link
        binding.aboutLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.imi.glass/about"))
            startActivity(intent)
        }

        // Sign Up button
        binding.signUpButton.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }

        // Upgrade plan button on profile page
        binding.upgradePlanButton.setOnClickListener {
            UsageLimitManager.showUpgradeDialog(this) {
                refreshUpgradeCard()
            }
        }
    }

    private fun loadUserData() {
        // Get user data from SharedPreferences
        val userName = sharedPref.getString("user_name", "User") ?: "User"
        val userEmail = sharedPref.getString("user_email", "user@example.com") ?: "user@example.com"
        val profileImageUrl = sharedPref.getString("profile_image_url", "") ?: ""

        // Set user name and email
        binding.userName.text = userName
        binding.userEmail.text = userEmail

        // Load profile image if available
        if (profileImageUrl.isNotEmpty()) {
            Glide.with(this)
                .load(profileImageUrl)
                .circleCrop()
                .into(binding.profileImage)
        }

        // Load dark mode setting
        val isDarkMode = sharedPref.getBoolean("dark_mode", false)
        binding.darkModeToggle.isChecked = isDarkMode

        // Load version info
        binding.versionText.text = "v${getVersionName()}"
    }

    private fun refreshUpgradeCard() {
        val currentPlan = UsageLimitManager.getCurrentPlan(this)
        binding.upgradeCardPlanStatus.text = "Current plan: ${currentPlan.title} (${currentPlan.priceLabel})"

        when (currentPlan) {
            UsageLimitManager.Plan.FREE -> {
                binding.upgradeCardTitle.text = "Upgrade to Premium"
                binding.upgradeCardSubtitle.text = "Unlock advanced Glasses AI features"
                binding.upgradePlanButton.text = "Upgrade"
            }
            UsageLimitManager.Plan.PRO -> {
                binding.upgradeCardTitle.text = "Upgrade to Ultra"
                binding.upgradeCardSubtitle.text = "Boost your limits for voice, vision, and chat"
                binding.upgradePlanButton.text = "Upgrade"
            }
            UsageLimitManager.Plan.ULTRA -> {
                binding.upgradeCardTitle.text = "Ultra Plan Active"
                binding.upgradeCardSubtitle.text = "You are on the highest plan with maximum limits"
                binding.upgradePlanButton.text = "Manage"
            }
        }
    }

    private fun getVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "2.0.1"
        } catch (e: Exception) {
            "2.0.1"
        }
    }

    private fun showManageDataDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Manage Data")
            .setMessage("Choose what to do with your data:")
            .setNegativeButton("Clear App Data") { dialog, which ->
                clearAppData()
            }
            .setNeutralButton("Export Data") { dialog, which ->
                // Export data functionality
                showToast("Data export feature coming soon")
            }
            .setPositiveButton("Cancel") { dialog, which ->
                dialog.dismiss()
            }
            .show()
    }

    private fun clearAppData() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Clear All Data?")
            .setMessage("This will delete all your saved preferences, conversation history, and settings. This action cannot be undone.")
            .setNegativeButton("DELETE") { dialog, which ->
                // Clear all SharedPreferences
                sharedPref.edit().clear().apply()
                getSharedPreferences("conversation_history", MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("user_memory_prefs", MODE_PRIVATE).edit().clear().apply()
                
                showToast("All data cleared successfully")
                finish()
            }
            .setPositiveButton("Cancel") { dialog, which ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("YES, LOGOUT") { dialog, which ->
                performLogout()
            }
            .setNegativeButton("Cancel") { dialog, which ->
                dialog.dismiss()
            }
            .show()
    }

    private fun performLogout() {
        // Clear authentication data
        sharedPref.edit()
            .remove("user_name")
            .remove("user_email")
            .remove("auth_token")
            .remove("is_logged_in")
            .apply()

        // Go to login screen
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    private fun openPlayStore() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        } catch (e: Exception) {
            showToast("Play Store not available")
        }
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
