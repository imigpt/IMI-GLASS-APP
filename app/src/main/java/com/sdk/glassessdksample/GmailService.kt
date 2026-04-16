package com.sdk.glassessdksample

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject

/**
 * Gmail Service for reading and sending emails
 * Uses Gmail REST API with OAuth 2.0 authentication
 */
class GmailService(private val context: Context) {
    
    private val TAG = "GmailService"
    private var accessToken: String? = null
    private val httpClient = OkHttpClient()
    private val gmailApiUrl = "https://www.googleapis.com/gmail/v1"
    
    private val scopes = arrayOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.send",
        "https://www.googleapis.com/auth/gmail.modify"
    )
    
    /**
     * Initialize Gmail service - get OAuth token
     */
    fun initializeGmail(callback: (Boolean) -> Unit) {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestScopes(Scope("https://www.googleapis.com/auth/gmail.readonly"))
                .build()
            
            val signInClient = GoogleSignIn.getClient(context, gso)
            val account = GoogleSignIn.getLastSignedInAccount(context)
            
            if (account != null) {
                Log.d(TAG, "✅ Gmail account found: ${account.email}")
                callback(true)
            } else {
                Log.w(TAG, "⚠️ No Gmail account signed in")
                callback(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Gmail: ${e.message}")
            callback(false)
        }
    }
    
    /**
     * Get unread email count
     */
    fun getUnreadEmailCount(callback: (String) -> Unit) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            callback("Please sign in with your Google account first")
            return
        }
        
        GlobalScope.launch(Dispatchers.Main) {
            try {
                val response = withContext(Dispatchers.IO) {
                    fetchGmailAPI("$gmailApiUrl/users/me/messages?q=is:unread&maxResults=1", account.idToken)
                }
                
                val jsonResponse = JSONObject(response)
                val unreadCount = if (jsonResponse.has("resultSizeEstimate")) {
                    jsonResponse.getInt("resultSizeEstimate")
                } else {
                    0
                }
                
                val message = if (unreadCount > 0) {
                    "You have $unreadCount unread email${if (unreadCount > 1) "s" else ""}"
                } else {
                    "You have no unread emails"
                }
                
                Log.d(TAG, "📧 Unread count: $unreadCount")
                callback(message)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error getting unread count: ${e.message}")
                callback("Unable to fetch unread emails")
            }
        }
    }
    
    /**
     * Get recent emails from inbox
     */
    fun getRecentEmails(maxResults: Int = 5, callback: (String) -> Unit) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            callback("Please sign in with your Google account first")
            return
        }
        
        GlobalScope.launch(Dispatchers.Main) {
            try {
                val messageListResponse = withContext(Dispatchers.IO) {
                    fetchGmailAPI("$gmailApiUrl/users/me/messages?maxResults=$maxResults", account.idToken)
                }
                
                val jsonResponse = JSONObject(messageListResponse)
                val messages = if (jsonResponse.has("messages")) jsonResponse.getJSONArray("messages") else null
                
                if (messages == null || messages.length() == 0) {
                    callback("No emails in inbox")
                    return@launch
                }
                
                val emailSummary = StringBuilder("Recent emails:\n")
                
                for (i in 0 until messages.length()) {
                    val msgId = messages.getJSONObject(i).getString("id")
                    try {
                        val msgResponse = withContext(Dispatchers.IO) {
                            fetchGmailAPI("$gmailApiUrl/users/me/messages/$msgId", account.idToken)
                        }
                        val msgJson = JSONObject(msgResponse)
                        val headers = msgJson.getJSONObject("payload").getJSONArray("headers")
                        
                        var subject = "(No subject)"
                        var from = "Unknown"
                        
                        for (j in 0 until headers.length()) {
                            val header = headers.getJSONObject(j)
                            when (header.getString("name")) {
                                "Subject" -> subject = header.getString("value")
                                "From" -> from = header.getString("value")
                            }
                        }
                        
                        emailSummary.append("${i + 1}. From: $from\n")
                        emailSummary.append("   Subject: $subject\n")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing message: ${e.message}")
                    }
                }
                
                callback(emailSummary.toString())
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error getting emails: ${e.message}")
                callback("Unable to fetch emails")
            }
        }
    }
    
    /**
     * Search emails by query
     */
    fun searchEmails(query: String, callback: (String) -> Unit) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            callback("Please sign in with your Google account first")
            return
        }
        
        GlobalScope.launch(Dispatchers.Main) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val response = withContext(Dispatchers.IO) {
                    fetchGmailAPI("$gmailApiUrl/users/me/messages?q=$encodedQuery&maxResults=5", account.idToken)
                }
                
                val jsonResponse = JSONObject(response)
                val messages = if (jsonResponse.has("messages")) jsonResponse.getJSONArray("messages") else null
                
                if (messages == null || messages.length() == 0) {
                    callback("No emails found matching: $query")
                    return@launch
                }
                
                val emailSummary = StringBuilder("Found ${messages.length()} email(s):\n")
                
                for (i in 0 until messages.length()) {
                    val msgId = messages.getJSONObject(i).getString("id")
                    try {
                        val msgResponse = withContext(Dispatchers.IO) {
                            fetchGmailAPI("$gmailApiUrl/users/me/messages/$msgId", account.idToken)
                        }
                        val msgJson = JSONObject(msgResponse)
                        val headers = msgJson.getJSONObject("payload").getJSONArray("headers")
                        
                        var subject = "(No subject)"
                        for (j in 0 until headers.length()) {
                            val header = headers.getJSONObject(j)
                            if (header.getString("name") == "Subject") {
                                subject = header.getString("value")
                                break
                            }
                        }
                        
                        emailSummary.append("${i + 1}. $subject\n")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing message: ${e.message}")
                    }
                }
                
                callback(emailSummary.toString())
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error searching emails: ${e.message}")
                callback("Search failed")
            }
        }
    }
    
    /**
     * Fetch from Gmail API using access token
     */
    private fun fetchGmailAPI(url: String, idToken: String?): String {
        val request = Request.Builder()
            .url(url)
            .apply {
                if (idToken != null) {
                    addHeader("Authorization", "Bearer $idToken")
                }
            }
            .build()
        
        val response = httpClient.newCall(request).execute()
        return response.body?.string() ?: ""
    }
    
    /**
     * Check if Gmail is ready
     */
    fun isGmailReady(): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }
}

