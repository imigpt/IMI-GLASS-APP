package com.sdk.glassessdksample.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sdk.glassessdksample.MainActivity
import com.sdk.glassessdksample.MoreActivity
import com.sdk.glassessdksample.ProfileActivity
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.ui.ChatActivity

/**
 * Thin wrapper kept for the screens that call it with an [AppCompatActivity].
 * Navigation is mode-aware: the "Home" tab resolves to the home screen of the
 * currently selected device mode ([DeviceType]) rather than being hardcoded,
 * so passing through shared screens (More, Profile, …) never flips the mode.
 */
object Mark1BottomNavManager {

    fun setup(activity: AppCompatActivity, nav: BottomNavigationView, selectedItemId: Int) {
        nav.selectedItemId = selectedItemId
        NavGlow.positionFor(nav, selectedItemId)

        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selectedItemId) return@setOnItemSelectedListener true
            val target: Class<*>? = when (item.itemId) {
                R.id.nav_home -> homeActivityFor(activity)
                R.id.nav_chat -> ChatActivity::class.java
                R.id.nav_more -> MoreActivity::class.java
                R.id.nav_profile -> ProfileActivity::class.java
                else -> null
            }
            target?.let {
                val intent = Intent(activity, it)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                activity.startActivity(intent)
                activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            true
        }
    }

    /** Home target depends on the currently selected device mode. */
    private fun homeActivityFor(activity: AppCompatActivity): Class<*> {
        return if (DevicePreferenceManager.getDeviceType(activity) == DeviceType.MARK1) {
            Mark1MainActivity::class.java
        } else {
            MainActivity::class.java
        }
    }
}
