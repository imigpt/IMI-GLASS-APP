package com.sdk.glassessdksample

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Simple launcher that directly opens Vision Chat
 * Can be used as a separate entry point or for testing
 */
class VisionChatLauncher : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Directly launch Vision Chat
        val intent = Intent(this, VisionChatActivity::class.java)
        startActivity(intent)
        
        // Close this launcher immediately
        finish()
    }
}
