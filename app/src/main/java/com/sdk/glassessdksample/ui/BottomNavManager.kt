package com.sdk.glassessdksample.ui

import android.app.Activity
import android.content.Intent
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sdk.glassessdksample.MainActivity
import com.sdk.glassessdksample.MoreActivity
import com.sdk.glassessdksample.ProfileActivity
import com.sdk.glassessdksample.R

/**
 * The app's single bottom navigation implementation, shared by every screen
 * that shows the pill in both Mark 1 and Mark 2 modes.
 *
 * Navigation is mode-aware: the Home tab resolves to the home screen of the
 * currently selected [DeviceType] at runtime, so passing through the shared
 * screens (Chat, More, Profile, Settings, Camera) never flips the mode.
 *
 * Tabs are siblings, not a stack. Every destination is launched with
 * CLEAR_TOP | SINGLE_TOP and every nav screen is `singleTop` in the manifest,
 * so switching tabs reuses the existing instance through `onNewIntent` instead
 * of piling up duplicate activities — which is what previously left the wrong
 * tab highlighted after returning from Chat.
 */
object BottomNavManager {

    /**
     * The four tabs on the pill, mapped to the screen each one opens.
     *
     * Camera and Settings are not tabs — they are reached from within More and
     * Profile and simply keep their parent tab highlighted while open.
     */
    private val DESTINATIONS: Map<Int, (Activity) -> Class<*>> = mapOf(
        R.id.nav_home to ::homeActivityFor,
        R.id.nav_chat to { _ -> ChatActivity::class.java },
        R.id.nav_more to { _ -> MoreActivity::class.java },
        R.id.nav_profile to { _ -> ProfileActivity::class.java }
    )

    /**
     * Wires up the pill for [activity], highlighting [currentTabId].
     *
     * Call from `onCreate`. Screens that can be resumed without being recreated
     * should also call [restoreSelection] from `onResume`.
     */
    @JvmStatic
    fun setup(nav: BottomNavigationView, currentTabId: Int, activity: Activity) {
        applyWindowInsets(nav)
        select(nav, currentTabId)

        nav.setOnItemSelectedListener { item ->
            if (item.itemId == currentTabId) return@setOnItemSelectedListener true
            val target = DESTINATIONS[item.itemId]?.invoke(activity)
                ?: return@setOnItemSelectedListener false

            if (target == activity::class.java) return@setOnItemSelectedListener true

            activity.startActivity(
                Intent(activity, target).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            // Leave the pill on the current tab. The destination highlights its
            // own tab when it comes up; moving it here makes the outgoing screen
            // flash the wrong selection during the transition.
            false
        }
    }

    /**
     * Re-asserts [currentTabId] without firing the navigation listener.
     *
     * Returning to a screen that was only stopped (not destroyed) leaves the
     * pill on whatever tab the user tapped to leave, so every nav screen calls
     * this from `onResume`.
     */
    @JvmStatic
    fun restoreSelection(nav: BottomNavigationView, currentTabId: Int) {
        select(nav, currentTabId)
    }

    /**
     * Checking the menu item marks the tab without dispatching to the item
     * listener — unlike assigning `selectedItemId`, which does fire it and
     * would re-trigger navigation on every resume.
     */
    private fun select(nav: BottomNavigationView, currentTabId: Int) {
        nav.menu.findItem(currentTabId)?.isChecked = true
        NavGlow.positionFor(nav, currentTabId)
    }

    /**
     * Floats the pill above the gesture bar / navigation bar.
     *
     * The window draws edge-to-edge (targetSdk 35+), so the layout's authored
     * 14dp bottom margin alone would leave the pill under the system bar by a
     * device-dependent amount.
     */
    private fun applyWindowInsets(nav: BottomNavigationView) {
        ViewCompat.setOnApplyWindowInsetsListener(nav) { view, insets ->
            applyInsets(
                view as BottomNavigationView,
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            )
            insets
        }
        ViewCompat.requestApplyInsets(nav)
    }

    /**
     * Offsets the pill by [bars] on top of its layout-authored margins.
     *
     * Public because a screen whose ancestor consumes the inset dispatch (Chat's
     * DrawerLayout) never reaches the listener above and must call this itself.
     */
    @JvmStatic
    fun applyInsets(nav: BottomNavigationView, bars: androidx.core.graphics.Insets) {
        val base = baseMargins(nav) ?: return
        nav.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = base.bottom + bars.bottom
            leftMargin = base.left + bars.left
            rightMargin = base.right + bars.right
        }
    }

    /**
     * The margins the layout declared, captured on first use and cached on the
     * view. Re-reading the live margins would compound the insets on every
     * dispatch (rotation, keyboard, multi-window).
     */
    private fun baseMargins(nav: BottomNavigationView): androidx.core.graphics.Insets? {
        (nav.getTag(R.id.nav_base_margins) as? androidx.core.graphics.Insets)?.let { return it }
        val params = nav.layoutParams as? ViewGroup.MarginLayoutParams ?: return null
        val base = androidx.core.graphics.Insets.of(
            params.leftMargin, 0, params.rightMargin, params.bottomMargin
        )
        nav.setTag(R.id.nav_base_margins, base)
        return base
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
