package com.sdk.glassessdksample.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.sdk.glassessdksample.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiAIClient {

    // System prompt that defines Imi Glass personality and capabilities
    private val systemPrompt = """
        You are Imi Glass, an intelligent AI assistant integrated into smart glasses.
        You were built and developed by Ajay Mehta.
        
        IMPORTANT BEHAVIOR:
        - Only introduce yourself when user specifically asks "who are you", "introduce yourself", "tell me about yourself" or similar
        - For all other questions, give direct answers without introducing yourself
        - Be concise and helpful
        
        Your capabilities include:
        - Making phone calls to contacts
        - Playing music on Spotify and other music apps
        - Playing videos on YouTube
        - Taking photos and recording videos
        - Providing GPS location and navigation
        - Tracking user location and giving directions
        - Searching the web for information
        - Getting latest news in Hindi and English
        - Answering questions with factual information
        - Having natural conversations in both Hindi and English
        
        Personality:
        - Friendly, helpful, and concise in responses
        - You understand and respond fluently in both Hindi and English
        - Keep responses brief and conversational (2-3 sentences max for most queries)
        - When introducing yourself, say: "I am Imi Glass, your intelligent AI assistant built by Ajay Mehta. I can help you make calls, play music on Spotify, play videos, take photos and videos, track your location, and answer any questions you have."
        
        Important:
        - Respond in the same language the user speaks (Hindi or English)
        - For action requests (calls, music, photos), acknowledge briefly then the system will perform the action
        - Provide factual, accurate information
        - If you don't know something, admit it honestly
        - Always mention that you were created by Ajay Mehta when asked about who built you or who is your developer
    """.trimIndent()

    // Using Gemini 2.5 Flash for fast, reliable TEXT responses
    // Enhanced Local Android TTS provides high-quality audio
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = BuildConfig.GEMINI_API_KEY,
        systemInstruction = com.google.ai.client.generativeai.type.content { text(systemPrompt) }
    )

    suspend fun chat(prompt: String): String {
        return try {
            val response = withContext(Dispatchers.IO) {
                generativeModel.generateContent(prompt)
            }
            val text = response.text
            if (text.isNullOrBlank()) {
                Log.w("GeminiAIClient", "Empty response.text from Gemini for prompt='$prompt'")
                "No response from Gemini."
            } else {
                text
            }
        } catch (e: Exception) {
            // Log full stacktrace for debugging
            Log.e("GeminiAIClient", "Error calling Gemini API", e)
            val msg = e.message ?: "An error occurred calling Gemini."
            val stackTrace = e.stackTraceToString()
            
            // Handle specific error codes
            return when {
                msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED") || stackTrace.contains("RESOURCE_EXHAUSTED") -> {
                    Log.e("GeminiAIClient", "Quota exhausted - rate limit hit")
                    "API quota exceeded. Please wait a moment and try again."
                }
                msg.contains("404") || msg.contains("Not Found", true) -> {
                    "Model not available for your key."
                }
                msg.contains("MissingFieldException") || stackTrace.contains("MissingFieldException") -> {
                    Log.e("GeminiAIClient", "JSON parsing error - likely an API error response")
                    "Service temporarily unavailable. Please try again."
                }
                msg.contains("401") || msg.contains("Unauthorized") -> {
                    "Invalid API key. Please check your configuration."
                }
                msg.contains("403") || msg.contains("Forbidden") -> {
                    "Access forbidden. Check API key permissions."
                }
                else -> {
                    "AI service error. Please try again later."
                }
            }
        }
    }

    // Overloaded chat function to handle history (optional)
    suspend fun chat(prompt: String, history: List<Pair<String, String>>): String {
        val fullPrompt = history.joinToString("\n") { "${it.first}: ${it.second}" } + "\nuser: $prompt"
        return chat(fullPrompt)
    }
    
    // Vision API - Analyze image and detect objects
    suspend fun analyzeImage(imageBytes: ByteArray, userPrompt: String = """Look at this image and describe EXACTLY what you see in detail. 
        |Be specific - mention brands, colors, text visible, object models/types. 
        |Don't give vague or generic answers. 
        |Describe as if explaining to someone who cannot see the image.""".trimMargin()): String {
        return try {
            val response = withContext(Dispatchers.IO) {
                // Convert ByteArray to Bitmap for Gemini Vision API
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: return@withContext null
                
                val imagePart = com.google.ai.client.generativeai.type.content {
                    image(bitmap)
                }
                val promptPart = com.google.ai.client.generativeai.type.content {
                    text(userPrompt)
                }
                
                // Use vision model for image analysis
                val visionModel = GenerativeModel(
                    modelName = "gemini-2.5-flash",
                    apiKey = BuildConfig.GEMINI_API_KEY
                )
                
                visionModel.generateContent(imagePart, promptPart)
            }
            
            val text = response?.text
            if (text.isNullOrBlank()) {
                Log.w("GeminiAIClient", "Empty vision response from Gemini")
                "I couldn't analyze the image."
            } else {
                text
            }
        } catch (e: Exception) {
            Log.e("GeminiAIClient", "Error calling Gemini Vision API", e)
            val msg = e.message ?: "Vision analysis error"
            
            when {
                msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED") -> {
                    "Vision API quota exceeded. Please wait a moment."
                }
                msg.contains("404") || msg.contains("Not Found", true) -> {
                    "Vision model not available."
                }
                else -> {
                    "I couldn't analyze the image. Please try again."
                }
            }
        }
    }
    

}
