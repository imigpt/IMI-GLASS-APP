package com.sdk.glassessdksample.ui

import android.app.Activity
import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sdk.glassessdksample.CameraActivity
import com.sdk.glassessdksample.MainActivity
import com.sdk.glassessdksample.MoreActivity
import com.sdk.glassessdksample.ProfileActivity
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.SettingsActivity

object BottomNavManager {

    fun setup(bottomNav: BottomNavigationView, currentTabId: Int, activity: Activity) {
        bottomNav.selectedItemId = currentTabId
        bottomNav.setOnItemSelectedListener { item ->
            if (item.itemId == currentTabId) {
                return@setOnItemSelectedListener true
            }
            navigate(activity, item.itemId)
            true
        }
    }

    private fun navigate(activity: Activity, destinationId: Int) {
        val target = when (destinationId) {
            R.id.nav_home -> MainActivity::class.java
            R.id.nav_camera -> CameraActivity::class.java
            R.id.nav_settings -> SettingsActivity::class.java
            R.id.nav_profile -> ProfileActivity::class.java
            R.id.nav_more -> MoreActivity::class.java
            else -> return
        }

        val intent = Intent(activity, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        activity.startActivity(intent)

        if (activity::class.java != target) {
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
