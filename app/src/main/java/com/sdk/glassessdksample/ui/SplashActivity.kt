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
        
        // Check if onboarding has been completed
        val sharedPreferences = getSharedPreferences("IMI_PREFS", MODE_PRIVATE)
        val hasCompletedOnboarding = sharedPreferences.getBoolean("onboarding_completed", false)
        
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = if (hasCompletedOnboarding) {
                Intent(this, com.sdk.glassessdksample.MainActivity::class.java)
            } else {
                Intent(this, OnboardingActivity::class.java)
            }
            startActivity(intent)
            finish()
        }, SPLASH_DISPLAY_LENGTH)
    }
}
