package com.sdk.glassessdksample.ui

import android.app.Activity
import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sdk.glassessdksample.MainActivity
import com.sdk.glassessdksample.MoreActivity
import com.sdk.glassessdksample.ProfileActivity
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.ui.ChatActivity

/**
 * Mode-aware bottom navigation.
 *
 * The app runs in one of two modes (Mark 1 / Mark 2), persisted via
 * [DevicePreferenceManager]. The "Home" tab must return the user to the home
 * screen of the mode they are currently in. Shared screens (More, Profile,
 * Camera, Settings) don't know the mode at compile time, so the Home target is
 * resolved at runtime from the persisted [DeviceType]. This keeps navigation
 * consistent regardless of which screens the user passed through to get here.
 */
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
            R.id.nav_home -> homeActivityFor(activity)
            R.id.nav_chat -> ChatActivity::class.java
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

    /** Home target depends on the currently selected device mode. */
    private fun homeActivityFor(activity: Activity): Class<*> {
        return if (DevicePreferenceManager.getDeviceType(activity) == DeviceType.MARK1) {
            Mark1MainActivity::class.java
        } else {
            MainActivity::class.java
        }
    }
}
