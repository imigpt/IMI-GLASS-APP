package com.sdk.glassessdksample.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sdk.glassessdksample.MoreActivity
import com.sdk.glassessdksample.ProfileActivity
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.ui.ChatActivity

object Mark1BottomNavManager {

    fun setup(activity: AppCompatActivity, nav: BottomNavigationView, selectedItemId: Int) {
        nav.selectedItemId = selectedItemId

        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selectedItemId) return@setOnItemSelectedListener true
            val intent: Intent? = when (item.itemId) {
                R.id.nav_home -> Intent(activity, Mark1MainActivity::class.java)
                R.id.nav_chat -> Intent(activity, ChatActivity::class.java)
                R.id.nav_more -> Intent(activity, MoreActivity::class.java)
                R.id.nav_profile -> Intent(activity, ProfileActivity::class.java)
                else -> null
            }
            intent?.let {
                it.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                activity.startActivity(it)
                activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            true
        }
    }
}
