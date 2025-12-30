package com.sdk.glassessdksample

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

/**
 * Dialog for configuring image upload settings
 */
class UploadSettingsDialog(private val context: Context) {
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("imi_prefs", AppCompatActivity.MODE_PRIVATE)
    
    fun show() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_upload_settings, null)
        
        val enableSwitch = dialogView.findViewById<Switch>(R.id.uploadEnabledSwitch)
        val urlInput = dialogView.findViewById<EditText>(R.id.uploadUrlInput)
        
        // Load current settings
        enableSwitch.isChecked = prefs.getBoolean("auto_upload_enabled", false)
        urlInput.setText(prefs.getString("image_upload_url", "http://10.0.2.2:8080/upload"))
        
        AlertDialog.Builder(context)
            .setTitle("Image Upload Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                // Save settings
                prefs.edit()
                    .putBoolean("auto_upload_enabled", enableSwitch.isChecked)
                    .putString("image_upload_url", urlInput.text.toString())
                    .apply()
                
                android.widget.Toast.makeText(
                    context,
                    "Upload settings saved",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
