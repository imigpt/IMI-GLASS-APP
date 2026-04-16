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
        
        // Temporarily bypass login for testing - set to true to skip auth
        val skipAuth = false // Change to false to enable login flow
        
        val isLoggedIn = sharedPreferences.getBoolean("is_logged_in", false) || skipAuth
        val hasCompletedOnboarding = sharedPreferences.getBoolean("onboarding_completed", false) || skipAuth
        
        Handler(Looper.getMainLooper()).postDelayed({
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
                        // User logged in and completed onboarding, go to main screen
                        Intent(this, com.sdk.glassessdksample.MainActivity::class.java)
                    }
                }
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback to MainActivity if something fails
                val fallbackIntent = Intent(this, com.sdk.glassessdksample.MainActivity::class.java)
                startActivity(fallbackIntent)
                finish()
            }
        }, SPLASH_DISPLAY_LENGTH)
    }
}
