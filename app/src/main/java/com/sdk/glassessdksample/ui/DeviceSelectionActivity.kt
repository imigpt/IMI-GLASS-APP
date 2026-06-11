package com.sdk.glassessdksample.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.MainActivity
import com.sdk.glassessdksample.R

class DeviceSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_selection)

        val btnMark1 = findViewById<LinearLayout>(R.id.btnSelectMark1)
        val btnMark1Action = findViewById<Button>(R.id.btnSelectMark1Action)
        val btnMark2 = findViewById<LinearLayout>(R.id.btnSelectMark2)
        val btnMark2Action = findViewById<Button>(R.id.btnSelectMark2Action)
        val btnInfo = findViewById<View>(R.id.btnInfo)

        val selectMark1 = View.OnClickListener {
            DevicePreferenceManager.setDeviceType(this, DeviceType.MARK1)
            startActivity(Intent(this, Mark1MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }

        val selectMark2 = View.OnClickListener {
            DevicePreferenceManager.setDeviceType(this, DeviceType.MARK2)
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }

        btnMark1.setOnClickListener(selectMark1)
        btnMark1Action.setOnClickListener(selectMark1)
        btnMark2.setOnClickListener(selectMark2)
        btnMark2Action.setOnClickListener(selectMark2)

        btnInfo.setOnClickListener {
            showConnectionGuide()
        }
    }

    private fun showConnectionGuide() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("How to Connect Your Glasses")
            .setMessage(
                "1. Open your phone's Bluetooth settings\n\n" +
                "2. Power on your IMI glasses\n\n" +
                "3. Look for \"IMI\" in the available devices list\n\n" +
                "4. Tap to pair\n\n" +
                "5. Return to this app — your glasses will be detected automatically"
            )
            .setPositiveButton("Got it", null)
            .show()
    }
}
