package com.sdk.glassessdksample.ui

import androidx.appcompat.app.AppCompatActivity

/**
 * A base activity for holding common logic. 
 */
abstract class BaseActivity : AppCompatActivity() {

    /**
     * Abstract method to enforce view setup in child activities.
     */
    abstract fun setupViews()
}
