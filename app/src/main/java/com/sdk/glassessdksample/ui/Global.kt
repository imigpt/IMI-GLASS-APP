package com.sdk.glassessdksample.ui

import android.view.View

/**
 * Safe Click Listener with Debounce (to avoid double click issues).
 * Works perfectly for Smart Glass AI commands & UI buttons.
 */

private const val CLICK_DEBOUNCE = 350L

fun View.setSafeOnClickListener(block: (View) -> Unit) {
    var lastClickTime = 0L
    
    this.setOnClickListener { view ->
        val current = System.currentTimeMillis()
        if (current - lastClickTime < CLICK_DEBOUNCE) return@setOnClickListener
        lastClickTime = current
        
        block(view)
    }
}
