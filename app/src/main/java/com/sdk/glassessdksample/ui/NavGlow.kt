package com.sdk.glassessdksample.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.sdk.glassessdksample.R

/**
 * Positions the orange accent glow at the top edge of the currently selected
 * bottom-navigation item.
 *
 * The glow used to be baked into [R.drawable.bg_home_nav] at a fixed x-offset,
 * so it only ever lined up with the first tab. Instead we add a thin gradient
 * strip as an overlay child of the nav bar ([BottomNavigationView] is a
 * [FrameLayout]) and move it to sit above whichever item is selected. Shared by
 * both [BottomNavManager] and [Mark1BottomNavManager].
 */
internal object NavGlow {

    private const val GLOW_TAG = "nav_selected_glow"
    private const val GLOW_WIDTH_DP = 36f
    private const val GLOW_HEIGHT_DP = 3f

    fun positionFor(bottomNav: BottomNavigationView, selectedItemId: Int) {
        bottomNav.post {
            val itemView = bottomNav.findViewById<View>(selectedItemId) ?: return@post

            val glow = bottomNav.findViewWithTag<View>(GLOW_TAG)
                ?: View(bottomNav.context).apply {
                    tag = GLOW_TAG
                    background = ContextCompat.getDrawable(context, R.drawable.nav_selected_glow)
                    bottomNav.addView(this)
                }

            val density = bottomNav.resources.displayMetrics.density
            val widthPx = (GLOW_WIDTH_DP * density).toInt()
            val heightPx = (GLOW_HEIGHT_DP * density).toInt()

            val params = (glow.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(widthPx, heightPx)
            params.width = widthPx
            params.height = heightPx
            params.gravity = Gravity.TOP or Gravity.START
            // Centre the strip horizontally over the selected item, pin to the top edge.
            params.leftMargin = itemView.left + (itemView.width - widthPx) / 2
            params.topMargin = 0
            glow.layoutParams = params
            glow.bringToFront()
        }
    }
}
