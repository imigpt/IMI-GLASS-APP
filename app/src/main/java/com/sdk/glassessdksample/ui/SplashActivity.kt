package com.sdk.glassessdksample.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.R
import java.io.IOException
import com.sdk.glassessdksample.ui.DevicePreferenceManager
import com.sdk.glassessdksample.ui.DeviceType
import com.sdk.glassessdksample.ui.DeviceSelectionActivity
import com.sdk.glassessdksample.ui.Mark1MainActivity

class SplashActivity : AppCompatActivity() {
    
    private val SPLASH_DISPLAY_LENGTH = 2000L // 2 seconds
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        
        // Load logo from assets
        val logoImageView = findViewById<ImageView>(R.id.ivLogo)
        try {
            val inputStream = assets.open("logo.png")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            logoImageView.setImageBitmap(bitmap)
            inputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        
        // Check authentication and onboarding status
        val sharedPreferences = getSharedPreferences("IMI_PREFS", MODE_PRIVATE)
        
        // Login flow is live: an unauthenticated launch goes to LoginActivity.
        // (Set to true only to temporarily bypass auth during local testing.)
        val skipAuth = false

        val isLoggedIn = sharedPreferences.getBoolean("is_logged_in", false) || skipAuth
        val hasCompletedOnboarding = true // onboarding screen hidden

        // Kick off the one-time migration + pull of Quick Notes / Meeting Minutes
        // from the backend. Runs on a background thread; no-op if not signed in.
        if (isLoggedIn) {
            com.sdk.glassessdksample.ui.sync.BackendSync.syncOnLaunch(applicationContext)
        }
        
        Handler(Looper.getMainLooper()).postDelayed({
            proceedFromSplash(isLoggedIn, hasCompletedOnboarding)
        }, SPLASH_DISPLAY_LENGTH)
    }

    private fun proceedFromSplash(isLoggedIn: Boolean, hasCompletedOnboarding: Boolean) {
            try {
                val intent = when {
                    !isLoggedIn -> {
                        // User not logged in, go to login screen
                        Intent(this, com.sdk.glassessdksample.LoginActivity::class.java)
                    }
                    !hasCompletedOnboarding -> {
                        // User logged in but hasn't completed onboarding
                        Intent(this, OnboardingActivity::class.java)
                    }
                    else -> {
                        // Go straight to the device the user last chose. The
                        // selector is a first-run step, not something to sit
                        // through on every launch — switching models afterwards
                        // lives in Profile → Switch Device.
                        when (DevicePreferenceManager.getDeviceType(this)) {
                            DeviceType.MARK1 -> Intent(this, Mark1MainActivity::class.java)
                            DeviceType.MARK2 -> Intent(this, com.sdk.glassessdksample.MainActivity::class.java)
                            // Nothing chosen yet: first run, so ask.
                            null -> Intent(this, DeviceSelectionActivity::class.java)
                        }
                    }
                }
                // Splash is the task root here, so clear it out from under the
                // destination rather than leaving Splash on the back stack for
                // Back to return to.
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                e.printStackTrace()
                // Something went wrong working out where to go; the selector is the
                // one screen that is always safe to show and lets the user proceed.
                val fallbackIntent = Intent(this, DeviceSelectionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(fallbackIntent)
                finish()
            }
    }
}
