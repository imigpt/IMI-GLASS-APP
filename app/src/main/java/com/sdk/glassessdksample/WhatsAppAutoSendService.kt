package com.sdk.glassessdksample

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.SharedPreferences
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service to automatically send WhatsApp messages
 * Detects when WhatsApp chat is opened and auto-clicks the Send button
 */
class WhatsAppAutoSendService : AccessibilityService() {
    companion object {
        private const val TAG = "WhatsAppAutoSend"
        private const val PREF_AUTO_SEND_ENABLED = "whatsapp_auto_send_enabled"
        private const val PREF_PENDING_MESSAGE = "whatsapp_pending_message"
        const val ACTION_SEND_WHATSAPP = "com.sdk.glassessdksample.SEND_WHATSAPP"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_MESSAGE = "message"
    }

    private lateinit var prefs: SharedPreferences
    private var isWaitingToSend = false
    private var messageAttempts = 0
    private val MAX_ATTEMPTS = 10

    override fun onCreate() {
        super.onCreate()
        prefs = this.getSharedPreferences("whatsapp_auto_send", Context.MODE_PRIVATE)
        Log.d(TAG, "WhatsAppAutoSendService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "WhatsAppAutoSendService connected")
        
        // Configure the service
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            packageNames = arrayOf("com.whatsapp", "com.whatsapp.w4b")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            }
        }
        this.serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Check if auto-send is enabled via shared preference
        val shouldAutoSend = prefs.getBoolean("should_auto_send", false)
        
        // Auto-detect WhatsApp window changes
        if (event.packageName?.toString()?.contains("whatsapp") == true) {
            Log.d(TAG, "WhatsApp event detected: ${event.eventType}")
        }
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // When WhatsApp window changes, activate waiting for send
                if (shouldAutoSend && event.packageName?.toString()?.contains("whatsapp") == true) {
                    isWaitingToSend = true
                    messageAttempts = 0
                    Log.d(TAG, "WhatsApp window opened, waiting for Send button...")
                    // Immediately try to find and click send button
                    trySendMessage()
                }
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (shouldAutoSend && isWaitingToSend) {
                    messageAttempts++
                    Log.d(TAG, "UI event detected, attempting to find Send button (attempt $messageAttempts/${MAX_ATTEMPTS})")
                    
                    // Add small delay before trying to ensure UI is ready
                    if (messageAttempts <= MAX_ATTEMPTS) {
                        trySendMessage()
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "WhatsAppAutoSendService interrupted")
    }

    /**
     * Attempts to find and click the Send button in WhatsApp
     */
    private fun trySendMessage() {
        val rootNode = rootInActiveWindow ?: run {
            Log.d(TAG, "No active window found")
            return
        }

        Log.d(TAG, "Searching for Send button in WhatsApp UI...")
        
        // Try multiple strategies to find Send button
        
        // Strategy 1: Look for nodes with specific content descriptions
        var sendButton = findNodeByDescription(rootNode, "send", arrayOf("Send", "send message", "Send message", "send"))
        if (sendButton != null && sendButton.isClickable) {
            Log.d(TAG, "Found Send button by description")
            performClick(sendButton)
            isWaitingToSend = false
            messageAttempts = 0
            return
        }
        
        // Strategy 2: Look by resource ID patterns
        val sendButtonIds = listOf(
            "send",
            "send_button", 
            "action_button_send",
            "entry_button_send",
            "iciButtonSend",
            "button_send"
        )
        
        for (buttonId in sendButtonIds) {
            sendButton = findNodeById(rootNode, buttonId)
            if (sendButton != null && sendButton.isClickable) {
                Log.d(TAG, "Found Send button with ID: $buttonId")
                performClick(sendButton)
                isWaitingToSend = false
                messageAttempts = 0
                return
            }
        }
        
        // Strategy 3: Search by class and content (last resort)
        sendButton = findSendButtonByClass(rootNode)
        if (sendButton != null && sendButton.isClickable) {
            Log.d(TAG, "Found Send button by class analysis")
            performClick(sendButton)
            isWaitingToSend = false
            messageAttempts = 0
        }
    }

    /**
     * Find button by analyzing class hierarchy (ImageButton, Button, etc)
     */
    private fun findSendButtonByClass(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Check if this node is a clickable button with send-related attributes
        val className = node.className?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        
        if (node.isClickable && (
            className.contains("Button") || 
            className.contains("ImageButton") ||
            className.contains("FloatingActionButton")
        )) {
            // Check if it's positioned like a send button (usually bottom right area)
            if (contentDesc.contains("send") || text.contains("send")) {
                return node
            }
        }
        
        // Recursively search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSendButtonByClass(child)
            if (result != null) return result
        }
        
        return null
    }

    /**
     * Finds accessibility node by resource ID
     */
    private fun findNodeById(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val nodeId = node.viewIdResourceName ?: ""
        if (nodeId.endsWith("/$id") || nodeId.endsWith(id)) {
            Log.d(TAG, "Found node by ID: $nodeId")
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeById(child, id)
            if (result != null) return result
        }

        return null
    }

    /**
     * Finds send button by text content
     */
    private fun findSendButtonByText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        
        if (text.contains("send") && node.isClickable && node.className?.contains("Button") == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSendButtonByText(child)
            if (result != null) return result
        }

        return null
    }

    /**
     * Finds node by accessibility content description
     */
    private fun findNodeByDescription(
        node: AccessibilityNodeInfo,
        keyword: String,
        descriptions: Array<String>
    ): AccessibilityNodeInfo? {
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        
        if (descriptions.any { contentDesc.contains(it.lowercase()) } && 
            node.isClickable && 
            node.className?.contains("Button") == true) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByDescription(child, keyword, descriptions)
            if (result != null) return result
        }

        return null
    }

    /**
     * Performs click on accessibility node with retry logic
     */
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        return try {
            Log.d(TAG, "Clicking button: ${node.contentDescription ?: node.text ?: "Unknown"}")
            
            // Try direct click
            val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Click result: $success")
            
            // If direct click doesn't work, try parent
            if (!success && node.parent != null) {
                Log.d(TAG, "Attempting click on parent node")
                node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking button: ${e.message}", e)
            false
        }
    }

    /**
     * Called by MainActivity to initiate auto-send
     */
    fun enableAutoSend() {
        isWaitingToSend = true
        messageAttempts = 0
        Log.d(TAG, "Auto-send enabled, waiting for Send button...")
    }

    /**
     * Called by MainActivity to disable auto-send
     */
    fun disableAutoSend() {
        isWaitingToSend = false
        messageAttempts = 0
        Log.d(TAG, "Auto-send disabled")
    }
}
