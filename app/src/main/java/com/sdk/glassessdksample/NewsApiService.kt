package com.sdk.glassessdksample

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

/**
 * Service for fetching news from NewsAPI
 * Get your free API key from: https://newsapi.org/register
 */
class NewsApiService(private val apiKey: String) {
    
    private val TAG = "NewsApiService"
    private val client = OkHttpClient()
    private val baseUrl = "https://newsapi.org/v2"
    
    /**
     * Fetch top headlines by location
     * @param country Country code (e.g., "in" for India, "us" for USA)
     * @param query Search query (e.g., "Mumbai", "Maharashtra", "technology")
     * @param callback Callback with news summary
     */
    fun getNews(
        country: String = "in",
        query: String? = null,
        callback: (String) -> Unit
    ) {
        // Check if API key is configured
        if (apiKey == "YOUR_API_KEY_HERE" || apiKey.isBlank()) {
            Log.w(TAG, "News API key not configured. Get free key from https://newsapi.org/register")
            callback("News API key not configured. You can get a free API key from newsapi.org by registering.")
            return
        }
        
        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host("newsapi.org")
            .addPathSegment("v2")
            .addPathSegment("top-headlines")
            .addQueryParameter("country", country)
            .addQueryParameter("apiKey", apiKey)
        
        query?.let {
            urlBuilder.addQueryParameter("q", it)
        }
        
        val request = Request.Builder()
            .url(urlBuilder.build())
            .build()
        
        Log.d(TAG, "Fetching news for country=$country, query=$query")
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "News fetch failed: ${e.message}")
                callback("Sorry, I couldn't fetch the news right now. Please check your internet connection.")
            }
            
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        val newsSummary = parseNewsResponse(body ?: "")
                        callback(newsSummary)
                    } else {
                        Log.e(TAG, "News API error: ${response.code} - ${response.message}")
                        when (response.code) {
                            401 -> callback("News API key is invalid. Please get a valid API key from newsapi.org")
                            429 -> callback("News API rate limit reached. Please wait a moment.")
                            else -> callback("Unable to fetch news. API returned error ${response.code}.")
                        }
                    }
                }
            }
        })
    }
    
    /**
     * Parse NewsAPI JSON response and create summary
     */
    private fun parseNewsResponse(jsonString: String): String {
        try {
            val json = JSONObject(jsonString)
            val status = json.getString("status")
            
            if (status != "ok") {
                return "No news available at the moment."
            }
            
            val articles = json.getJSONArray("articles")
            
            if (articles.length() == 0) {
                return "No news found for your query."
            }
            
            val newsItems = mutableListOf<String>()
            val maxArticles = minOf(5, articles.length())
            
            for (i in 0 until maxArticles) {
                val article = articles.getJSONObject(i)
                val title = article.optString("title", "")
                val source = article.optJSONObject("source")?.optString("name", "")
                
                if (title.isNotEmpty()) {
                    newsItems.add("${i + 1}. $title ${if (source?.isNotEmpty() == true) "- $source" else ""}")
                }
            }
            
            return if (newsItems.isNotEmpty()) {
                "Here are the top headlines:\n\n${newsItems.joinToString("\n\n")}"
            } else {
                "No news articles available."
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing news: ${e.message}")
            return "Error processing news data."
        }
    }
    
    /**
     * Search news by keywords (for district/city specific news)
     */
    fun searchNews(
        searchQuery: String,
        callback: (String) -> Unit
    ) {
        // Check if API key is configured
        if (apiKey == "YOUR_API_KEY_HERE" || apiKey.isBlank()) {
            Log.w(TAG, "News API key not configured")
            callback("News API key not configured. Get a free API key from newsapi.org")
            return
        }
        
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("newsapi.org")
            .addPathSegment("v2")
            .addPathSegment("everything")
            .addQueryParameter("q", searchQuery)
            .addQueryParameter("language", "en")
            .addQueryParameter("sortBy", "publishedAt")
            .addQueryParameter("apiKey", apiKey)
            .build()
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        Log.d(TAG, "Searching news for: $searchQuery")
        
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "News search failed: ${e.message}")
                callback("Sorry, couldn't search for news right now.")
            }
            
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        val newsSummary = parseNewsResponse(body ?: "")
                        callback(newsSummary)
                    } else {
                        Log.e(TAG, "News search error: ${response.code} - ${response.message}")
                        when (response.code) {
                            401 -> callback("News API key is invalid")
                            429 -> callback("News API rate limit reached")
                            else -> callback("News search failed with error ${response.code}.")
                        }
                    }
                }
            }
        })
    }
}
