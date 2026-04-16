package com.sdk.glassessdksample.ui

import com.sdk.glassessdksample.RemoteConfigManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sdk.glassessdksample.BuildConfig
import com.sdk.glassessdksample.R
    import com.sdk.glassessdksample.WakeWordGate
import com.sdk.glassessdksample.ui.wifi.AlbumDownloader
import com.sdk.glassessdksample.utils.SafeBleCommandHelper
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ChatActivity - Unified seamless chat experience
 * 
 * FEATURES:
 * 1. UNIFIED CHAT HISTORY - Text + images in one continuous thread
 * 2. INLINE IMAGE CAPTURE - Capture from glasses without leaving chat
 * 3. MULTI-MODAL CONTEXT - AI remembers all images + conversation history
 * 
 * The AI automatically detects when the user wants vision analysis and 
 * captures images inline, analyzing them on-the-fly without switching screens.
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UnifiedChat"
        private const val MAX_AI_CONTEXT_CHARS = 1000
        private const val MAX_AI_HISTORY_CHARS = 800
        private const val MAX_AI_HISTORY_MESSAGES = 6
        
        // Action to receive images from VisionChatActivity or other sources
        const val ACTION_IMAGE_RECEIVED = "com.sdk.glassessdksample.IMAGE_RECEIVED"
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_AUTO_VISION = "auto_vision"
        const val EXTRA_VISION_QUERY = "vision_query"
    }

    // --- UI Views ---
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: LinearLayout
    private lateinit var rvChatMessages: RecyclerView
    private lateinit var rvConversations: RecyclerView
    private lateinit var etChatInput: EditText
    private lateinit var btnSendMessage: ImageView
    private lateinit var btnMenu: ImageView
    private lateinit var btnNewChat: View
    private lateinit var btnVoiceInput: ImageView
    private lateinit var btnAddAttachment: ImageView
    private lateinit var etSearchConversations: EditText
    
    // Action chips
    private lateinit var chipCreateImage: TextView
    private lateinit var chipSummarize: TextView
    private lateinit var chipDeepThinking: TextView

    // --- Adapters ---
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var conversationAdapter: ConversationAdapter
    
    // --- Data ---
    private val conversations = mutableListOf<Conversation>()
    private var currentConversation: Conversation? = null
    
    // --- Services ---
    private lateinit var prefs: SharedPreferences
    private lateinit var geminiClient: GeminiAIClient
    private lateinit var conversationMemory: ConversationMemory
    private lateinit var albumDownloader: AlbumDownloader
    private lateinit var memoryManager: UserMemoryManager  // Auto-learning AI memory
    private var visionModel: GenerativeModel? = null
    
    private val gson = Gson()
    
    // --- Vision State ---
    private var isCapturingImage = false
    private var lastCapturedImagePath: String? = null
    private var lastCapturedBitmap: Bitmap? = null
    private var cachedGlassIp: String? = null
    
    // --- Broadcast receiver for images from other activities ---
    private val imageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val imagePath = intent?.getStringExtra(EXTRA_IMAGE_PATH) ?: return
            val query = intent.getStringExtra(EXTRA_VISION_QUERY) ?: "Describe what you see"
            lifecycleScope.launch {
                handleInlineImageCapture(imagePath, query)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        
        initViews()
        initServices()
        loadConversations()
        setupRecyclerViews()
        setupDrawer()
        setupClickListeners()
        registerImageReceiver()
        
        // Create or load first conversation
        if (conversations.isEmpty()) {
            createNewConversation()
        } else {
            switchToConversation(conversations[0])
        }
        
        // Handle incoming vision intent
        handleVisionIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleVisionIntent(it) }
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)
        rvChatMessages = findViewById(R.id.rvChatMessages)
        rvConversations = findViewById(R.id.rvConversations)
        etChatInput = findViewById(R.id.etChatInput)
        btnSendMessage = findViewById(R.id.btnSendMessage)
        btnMenu = findViewById(R.id.btnMenu)
        btnNewChat = findViewById(R.id.btnNewChat)
        btnVoiceInput = findViewById(R.id.btnVoiceInput)
        etSearchConversations = findViewById(R.id.etSearchConversations)
        btnAddAttachment = findViewById(R.id.btnAddAttachment)
        
        chipCreateImage = findViewById(R.id.chipCreateImage)
        chipSummarize = findViewById(R.id.chipSummarize)
        chipDeepThinking = findViewById(R.id.chipDeepThinking)
        
        prefs = getSharedPreferences("IMI_CHAT_PREFS", MODE_PRIVATE)
    }

    private fun initServices() {
        geminiClient = GeminiAIClient(
            context = this,
            chatModelName = GeminiAIClient.CHAT_MODEL_FLASH_LITE,
            fallbackChatModelName = null
        )
        conversationMemory = ConversationMemory(this)
        albumDownloader = AlbumDownloader(this)
        memoryManager = UserMemoryManager(this)  // Initialise auto-learning memory
        
        // Initialize vision model for image analysis
        try {
            visionModel = GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = RemoteConfigManager.geminiApiKey
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vision model", e)
        }
        
        // Load cached glass IP
        cachedGlassIp = prefs.getString("cached_glass_ip", null)
    }
    
    private fun registerImageReceiver() {
        val filter = IntentFilter(ACTION_IMAGE_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(imageReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(imageReceiver, filter)
        }
    }
    
    private fun handleVisionIntent(intent: Intent) {
        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val autoVision = intent.getBooleanExtra(EXTRA_AUTO_VISION, false)
        val visionQuery = intent.getStringExtra(EXTRA_VISION_QUERY)
        
        if (imagePath != null) {
            lifecycleScope.launch {
                handleInlineImageCapture(imagePath, visionQuery ?: "Describe what you see in this image")
            }
        } else if (autoVision) {
            lifecycleScope.launch {
                captureAndAnalyzeFromGlasses(visionQuery ?: "What do you see?")
            }
        }
    }

    private fun setupRecyclerViews() {
        // Chat messages RecyclerView with image click support
        chatAdapter = ChatAdapter(
            messages = mutableListOf(),
            onImageClick = { message ->
                // Could open full-screen image viewer here
                Toast.makeText(this, "Image from conversation", Toast.LENGTH_SHORT).show()
            }
        )
        rvChatMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity)
            adapter = chatAdapter
        }
        
        // Conversations RecyclerView
        conversationAdapter = ConversationAdapter(
            conversations = conversations,
            onConversationClick = { conversation ->
                switchToConversation(conversation)
                drawerLayout.closeDrawer(GravityCompat.START)
            },
            onDeleteClick = { conversation, position ->
                deleteConversation(conversation, position)
            }
        )
        rvConversations.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity)
            adapter = conversationAdapter
        }
    }

    private fun setupDrawer() {
        btnMenu.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }
    }

    private fun setupClickListeners() {
        btnSendMessage.setOnClickListener {
            sendMessage()
        }
        
        btnNewChat.setOnClickListener {
            createNewConversation()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        
        btnVoiceInput.setOnClickListener {
            Toast.makeText(this, "Voice input coming soon", Toast.LENGTH_SHORT).show()
        }
        
        // ✨ CAMERA BUTTON - Inline image capture from glasses!
        btnAddAttachment.setOnClickListener {
            if (isCapturingImage) {
                Toast.makeText(this, "Already capturing...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            captureAndAnalyzeFromGlasses("Describe what you see in detail")
        }
        
        // Action chips
        chipCreateImage.setOnClickListener {
            // Changed: Now triggers vision capture instead of text
            captureAndAnalyzeFromGlasses("Describe what's in front of me")
        }
        
        chipSummarize.setOnClickListener {
            summarizeConversation()
        }
        
        chipDeepThinking.setOnClickListener {
            etChatInput.setText("Use deep thinking to analyze")
        }
        
        // Send on Enter key
        etChatInput.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }
    }

    // ========================================================================
    // FEATURE 1: UNIFIED CHAT HISTORY
    // Text + images in one continuous conversation thread
    // ========================================================================

    private fun sendMessage() {
        val message = etChatInput.text.toString().trim()
        if (message.isEmpty()) return

        val wakeWordInput = WakeWordGate.parse(message)
        
        val conversation = currentConversation ?: return
        
        // Add user message
        val chatMessage = ChatMessage(text = message, isFromUser = true, isFinal = true)
        conversation.messages.add(chatMessage)
        chatAdapter.addMessage(chatMessage)
        scrollToBottom()
        
        etChatInput.setText("")
        
        // Update conversation preview
        conversation.updatePreview()
        if (conversation.messages.size == 1) {
            val previewSource = if (wakeWordInput.cleanedInput.isNotBlank()) {
                wakeWordInput.cleanedInput
            } else {
                message
            }
            conversation.title = previewSource.take(30) + if (previewSource.length > 30) "..." else ""
        }
        conversationAdapter.notifyDataSetChanged()
        saveConversations()

        val cleanedMessage = if (wakeWordInput.hasWakePhrase) {
            wakeWordInput.cleanedInput
        } else {
            message
        }
        if (wakeWordInput.hasWakePhrase && cleanedMessage.isBlank()) {
            Log.d(TAG, "Wake phrase received without a follow-up query")
            Toast.makeText(this, "Say your question after 'Hey Imi'", Toast.LENGTH_SHORT).show()
            return
        }

        // Record only the cleaned user query for AI context/memory.
        conversationMemory.recordMessage(cleanedMessage, isFromUser = true)
        
        // ✨ FEATURE 2: INLINE IMAGE CAPTURE
        // Auto-detect if user wants vision and capture on-the-fly
        if (conversationMemory.isVisionRequest(cleanedMessage)) {
            Log.d(TAG, "Vision request detected: $cleanedMessage")
            captureAndAnalyzeFromGlasses(cleanedMessage)
            return
        }
        
        // ✨ FEATURE 3: MULTI-MODAL CONTEXT AWARENESS
        // Check if user is referring to a previous image
        val referencedImage = conversationMemory.detectImageReference(cleanedMessage)
        if (referencedImage != null) {
            Log.d(TAG, "User referenced previous image: ${referencedImage.id}")
            getAIResponseWithImageContext(cleanedMessage, referencedImage)
            return
        }
        
        // Normal text response with context
        getAIResponse(cleanedMessage)
    }

    // ========================================================================
    // FEATURE 2: INLINE IMAGE CAPTURE FROM CHAT
    // Captures from glasses and inserts into conversation seamlessly
    // ========================================================================

    /**
     * Capture image from glasses and analyze it inline - all without leaving chat
     */
    private fun captureAndAnalyzeFromGlasses(userQuery: String) {
        if (isCapturingImage) return
        isCapturingImage = true
        
        val conversation = currentConversation ?: run {
            isCapturingImage = false
            return
        }
        
        // Show system message
        val captureMsg = ChatMessage(
            text = "📸 Capturing image from glasses...",
            isFromUser = false,
            isFinal = false,
            messageType = MessageType.SYSTEM
        )
        conversation.messages.add(captureMsg)
        chatAdapter.addMessage(captureMsg)
        scrollToBottom()
        
        lifecycleScope.launch {
            try {
                // Step 1: Trigger glass camera via BLE
                val cameraSuccess = triggerGlassCamera()
                
                if (!cameraSuccess) {
                    updateSystemMessage(conversation, "⚠️ Could not trigger glass camera. Check BLE connection.")
                    isCapturingImage = false
                    return@launch
                }
                
                updateSystemMessage(conversation, "📸 Photo taken! Downloading from glasses...")
                
                // Step 2: Wait for photo to save
                delay(3000)
                
                // Step 3: Download image from glasses
                val imagePath = downloadLatestImage()
                
                if (imagePath == null) {
                    updateSystemMessage(conversation, "⚠️ Could not download image. Check WiFi connection to glasses.")
                    isCapturingImage = false
                    return@launch
                }
                
                // Step 4: Insert image inline in chat
                handleInlineImageCapture(imagePath, userQuery)
                
                // Remove the system message
                removeLastSystemMessage(conversation)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in capture flow", e)
                updateSystemMessage(conversation, "⚠️ Error: ${e.message}")
            } finally {
                isCapturingImage = false
            }
        }
    }
    
    /**
     * Handle an image that's been captured (from glasses or received externally)
     * Inserts it inline in the chat and triggers AI analysis
     */
    private suspend fun handleInlineImageCapture(imagePath: String, userQuery: String) {
        val conversation = currentConversation ?: return
        
        lastCapturedImagePath = imagePath
        
        // Load bitmap for analysis
        try {
            lastCapturedBitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(imagePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap", e)
        }
        
        // ✨ Add image inline in the unified chat thread
        withContext(Dispatchers.Main) {
            val imageMessage = ChatMessage(
                text = "📸 Image captured",
                isFromUser = true,
                isFinal = true,
                imagePath = imagePath,
                messageType = MessageType.IMAGE
            )
            conversation.messages.add(imageMessage)
            conversation.hasVisionContent = true
            chatAdapter.addMessage(imageMessage)
            scrollToBottom()
        }
        
        // Show analyzing indicator
        val analyzingMsg = ChatMessage(
            text = "🔍 Analyzing image...",
            isFromUser = false,
            isFinal = false,
            messageType = MessageType.SYSTEM
        )
        withContext(Dispatchers.Main) {
            conversation.messages.add(analyzingMsg)
            chatAdapter.addMessage(analyzingMsg)
            scrollToBottom()
        }
        
        // Analyze with Gemini Vision
        try {
            val analysisResult = analyzeImageWithGemini(imagePath, userQuery)
            
            // Remove analyzing message
            withContext(Dispatchers.Main) {
                removeLastSystemMessage(conversation)
            }
            
            // Add AI's vision analysis as a unified message with the image
            withContext(Dispatchers.Main) {
                val aiVisionMessage = ChatMessage(
                    text = analysisResult,
                    isFromUser = false,
                    isFinal = true,
                    imagePath = null, // Don't duplicate the image
                    messageType = MessageType.VISION_RESULT,
                    imageDescription = analysisResult.take(200)
                )
                conversation.messages.add(aiVisionMessage)
                chatAdapter.addMessage(aiVisionMessage)
                scrollToBottom()
                
                // Record in memory
                val imageId = conversationMemory.recordImage(
                    imagePath = imagePath,
                    aiDescription = analysisResult,
                    userQuery = userQuery,
                    conversationId = conversation.id
                )
                conversation.imageIds.add(imageId)
                conversationMemory.recordMessage(analysisResult, isFromUser = false, hasImage = true, imageId = imageId)
                
                conversation.updatePreview()
                conversationAdapter.notifyDataSetChanged()
                saveConversations()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Vision analysis error", e)
            withContext(Dispatchers.Main) {
                removeLastSystemMessage(conversation)
                val errorMsg = ChatMessage(
                    text = "⚠️ Could not analyze image: ${e.message}",
                    isFromUser = false,
                    isFinal = true,
                    messageType = MessageType.SYSTEM
                )
                conversation.messages.add(errorMsg)
                chatAdapter.addMessage(errorMsg)
                scrollToBottom()
            }
        }
    }
    
    /**
     * Trigger the glass camera via BLE
     */
    private suspend fun triggerGlassCamera(): Boolean = withContext(Dispatchers.IO) {
        var success = false
        try {
            val latch = CountDownLatch(1)
            SafeBleCommandHelper.takePhoto { result, error ->
                success = result
                if (error != null) Log.e(TAG, "Camera trigger error: $error")
                latch.countDown()
            }
            latch.await(5, TimeUnit.SECONDS)
            delay(500)
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering camera", e)
        }
        success
    }
    
    /**
     * Download the latest image from glasses via HTTP
     */
    private suspend fun downloadLatestImage(): String? = withContext(Dispatchers.IO) {
        try {
            // Try known glass IPs
            val ipsToTry = mutableListOf<String>()
            cachedGlassIp?.let { ipsToTry.add(it) }
            ipsToTry.addAll(listOf("192.168.49.1", "192.168.43.1", "192.168.1.1"))
            
            for (ip in ipsToTry) {
                try {
                    val files = albumDownloader.fetchConfig(ip)
                    val imageFiles = files.filter { item ->
                        val name = item.fileName.lowercase()
                        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                    }
                    
                    if (imageFiles.isNotEmpty()) {
                        // Get the latest image
                        val latestFile = imageFiles.last()
                        val outputDir = File(cacheDir, "vision_images")
                        if (!outputDir.exists()) outputDir.mkdirs()
                        
                        val downloadedFile = albumDownloader.downloadFile(ip, latestFile.fileName, outputDir)
                        if (downloadedFile != null && downloadedFile.exists()) {
                            // Cache the working IP
                            cachedGlassIp = ip
                            prefs.edit().putString("cached_glass_ip", ip).apply()
                            
                            Log.d(TAG, "Downloaded image: ${downloadedFile.absolutePath}")
                            return@withContext downloadedFile.absolutePath
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch from $ip: ${e.message}")
                    continue
                }
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading image", e)
            null
        }
    }
    
    /**
     * Analyze an image using Gemini Vision API
     */
    private suspend fun analyzeImageWithGemini(imagePath: String, userQuery: String): String {
        return withContext(Dispatchers.IO) {
            val bitmap = BitmapFactory.decodeFile(imagePath)
                ?: throw Exception("Could not decode image")
            
            // Build context-aware prompt using conversation memory
            val contextPrompt = conversationMemory.buildVisionContextPrompt(userQuery)
            
            val fullPrompt = """
                You are Imi Glass AI assistant. Analyze this image and respond naturally.
                
                $contextPrompt
                
                Be descriptive and specific. Mention brands, colors, text, objects visible.
                Keep response conversational and helpful (2-4 sentences).
                If the user asked a specific question about the image, answer that directly.
            """.trimIndent()
            
            val model = visionModel ?: throw Exception("Vision model not initialized")

            if (!UsageLimitManager.tryConsume(this@ChatActivity, TokenUsageTracker.Mode.SEEING)) {
                UsageLimitManager.promptUpgradeIfPossible(this@ChatActivity, TokenUsageTracker.Mode.SEEING)
                return@withContext UsageLimitManager.limitReachedMessage(TokenUsageTracker.Mode.SEEING)
            }
            
            val response = model.generateContent(
                content { image(bitmap) },
                content { text(fullPrompt) }
            )

            response.usageMetadata?.let {
                TokenUsageTracker.track(this@ChatActivity, TokenUsageTracker.Mode.SEEING, it)
            }
            
            response.text ?: "I couldn't analyze this image."
        }
    }

    // ========================================================================
    // FEATURE 3: MULTI-MODAL CONTEXT AWARENESS
    // AI remembers all images and can reference them in conversation
    // ========================================================================

    /**
     * Get AI response with full multi-modal context
     */
    private fun getAIResponse(userMessage: String) {
        lifecycleScope.launch {
            try {
                val conversation = currentConversation ?: return@launch
                
                // Show loading message
                val loadingMessage = ChatMessage(
                    text = "Thinking...",
                    isFromUser = false,
                    isFinal = false
                )
                conversation.messages.add(loadingMessage)
                chatAdapter.addMessage(loadingMessage)
                scrollToBottom()
                
                // Build compact context payload to reduce token usage.
                val contextPrompt = compactPromptText(
                    conversationMemory.buildContextPrompt(userMessage),
                    MAX_AI_CONTEXT_CHARS
                )
                
                // Also include compact conversation-local history.
                val conversationHistory = compactPromptText(
                    conversation.buildHistoryForAI(maxMessages = MAX_AI_HISTORY_MESSAGES),
                    MAX_AI_HISTORY_CHARS
                )
                
                val fullPrompt = if (conversationHistory.isNotBlank()) {
                    """
                    User message: $userMessage
                    
                    Context (compact):
                    $contextPrompt
                    
                    Recent chat:
                    $conversationHistory
                    
                    Reply briefly and directly. Use at most 2 sentences unless user asks for detail.
                    """.trimIndent()
                } else {
                    userMessage
                }
                
                val response = withContext(Dispatchers.IO) {
                    geminiClient.chat(fullPrompt)
                }
                
                // Remove loading message
                conversation.messages.removeAt(conversation.messages.size - 1)
                chatAdapter.notifyDataSetChanged()
                
                // Add AI response
                val aiMessage = ChatMessage(
                    text = response,
                    isFromUser = false,
                    isFinal = true
                )
                conversation.messages.add(aiMessage)
                chatAdapter.addMessage(aiMessage)
                scrollToBottom()
                
                // Record in memory
                conversationMemory.recordMessage(response, isFromUser = false)

                // ✨ Auto-learn from what the user said – no manual input needed
                memoryManager.learnFromUserMessage(userMessage)
                memoryManager.incrementMessageStats(isNewConversation = false)

                // Update preview
                conversation.updatePreview()
                conversationAdapter.notifyDataSetChanged()
                saveConversations()
                
            } catch (e: Exception) {
                handleAIError(e)
            }
        }
    }
    
    /**
     * Get AI response when user references a previous image
     * Re-loads the image and includes its context in the prompt
     */
    private fun getAIResponseWithImageContext(userMessage: String, referencedImage: ImageMemory) {
        lifecycleScope.launch {
            try {
                val conversation = currentConversation ?: return@launch
                
                // Show loading with image reference indicator
                val loadingMessage = ChatMessage(
                    text = "🔗 Looking at the referenced image...",
                    isFromUser = false,
                    isFinal = false,
                    referencedImageId = referencedImage.id
                )
                conversation.messages.add(loadingMessage)
                chatAdapter.addMessage(loadingMessage)
                scrollToBottom()
                
                // Check if we can re-analyze the image
                val imageFile = File(referencedImage.imagePath)
                val response: String
                
                if (imageFile.exists() && visionModel != null) {
                    // Re-analyze the image with the new question
                    response = withContext(Dispatchers.IO) {
                        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        if (bitmap != null) {
                            if (!UsageLimitManager.tryConsume(this@ChatActivity, TokenUsageTracker.Mode.SEEING)) {
                                UsageLimitManager.promptUpgradeIfPossible(this@ChatActivity, TokenUsageTracker.Mode.SEEING)
                                return@withContext UsageLimitManager.limitReachedMessage(TokenUsageTracker.Mode.SEEING)
                            }

                            val contextPrompt = conversationMemory.buildVisionContextPrompt(
                                userMessage, referencedImage
                            )
                            
                            val model = visionModel!!
                            val result = model.generateContent(
                                content { image(bitmap) },
                                content { text("""
                                    The user is asking about an image they shared earlier.
                                    Previous description: ${referencedImage.description}
                                    Their original question was: "${referencedImage.userQuery}"
                                    
                                    Now they are asking: "$userMessage"
                                    
                                    $contextPrompt
                                    
                                    Respond naturally and helpfully.
                                """.trimIndent()) }
                            )

                            result.usageMetadata?.let {
                                TokenUsageTracker.track(this@ChatActivity, TokenUsageTracker.Mode.SEEING, it)
                            }
                            result.text ?: "I couldn't re-analyze that image."
                        } else {
                            // Fall back to text-only with context
                            val compactContext = compactPromptText(
                                conversationMemory.buildContextPrompt(userMessage),
                                MAX_AI_CONTEXT_CHARS
                            )
                            geminiClient.chat(compactContext)
                        }
                    }
                } else {
                    // Image file no longer exists, use text context with image description
                    val contextPrompt = """
                        The user previously shared an image. Here's what was in it:
                        "${referencedImage.description}"
                        They originally asked: "${referencedImage.userQuery}"
                        
                        Now they're asking: "$userMessage"
                        
                        Respond naturally using the image context provided.
                    """.trimIndent()
                    
                    response = withContext(Dispatchers.IO) {
                        geminiClient.chat(contextPrompt)
                    }
                }
                
                // Remove loading message
                conversation.messages.removeAt(conversation.messages.size - 1)
                chatAdapter.notifyDataSetChanged()
                
                // Add AI response with image reference
                val aiMessage = ChatMessage(
                    text = response,
                    isFromUser = false,
                    isFinal = true,
                    referencedImageId = referencedImage.id
                )
                conversation.messages.add(aiMessage)
                chatAdapter.addMessage(aiMessage)
                scrollToBottom()
                
                // Record in memory
                conversationMemory.recordMessage(response, isFromUser = false, hasImage = true, imageId = referencedImage.id)

                // ✨ Auto-learn from what the user said
                memoryManager.learnFromUserMessage(userMessage)
                memoryManager.incrementMessageStats(isNewConversation = false)

                conversation.updatePreview()
                conversationAdapter.notifyDataSetChanged()
                saveConversations()
                
            } catch (e: Exception) {
                handleAIError(e)
            }
        }
    }

    private fun compactPromptText(text: String, maxChars: Int): String {
        val normalized = text.replace("\r", "").trim()
        if (normalized.length <= maxChars) return normalized
        return normalized.takeLast(maxChars)
    }
    
    private fun handleAIError(e: Exception) {
        e.printStackTrace()
        
        val conversation = currentConversation ?: return
        
        // Remove loading message if exists
        if (conversation.messages.isNotEmpty() && !conversation.messages.last().isFinal) {
            conversation.messages.removeAt(conversation.messages.size - 1)
        }
        
        val errorMessage = ChatMessage(
            text = "Sorry, I encountered an error. Please try again.",
            isFromUser = false,
            isFinal = true,
            messageType = MessageType.SYSTEM
        )
        conversation.messages.add(errorMessage)
        chatAdapter.notifyDataSetChanged()
        scrollToBottom()
        
        Toast.makeText(
            this@ChatActivity,
            "Error: ${e.message}",
            Toast.LENGTH_SHORT
        ).show()
    }
    
    // ========================================================================
    // HELPER METHODS
    // ========================================================================
    
    private fun updateSystemMessage(conversation: Conversation, newText: String) {
        if (conversation.messages.isNotEmpty()) {
            val lastMsg = conversation.messages.last()
            if (lastMsg.messageType == MessageType.SYSTEM && !lastMsg.isFinal) {
                lastMsg.text = newText
                chatAdapter.notifyDataSetChanged()
                return
            }
        }
    }
    
    private fun removeLastSystemMessage(conversation: Conversation) {
        if (conversation.messages.isNotEmpty()) {
            val lastMsg = conversation.messages.last()
            if (lastMsg.messageType == MessageType.SYSTEM) {
                conversation.messages.removeAt(conversation.messages.size - 1)
                chatAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun summarizeConversation() {
        val conversation = currentConversation ?: return
        
        if (conversation.messages.isEmpty()) {
            Toast.makeText(this, "No messages to summarize", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Build enhanced conversation history including image notes
        val conversationText = conversation.buildHistoryForAI()
        
        etChatInput.setText("Please summarize this conversation:\n$conversationText")
        sendMessage()
    }

    private fun createNewConversation() {
        val newConversation = Conversation(
            title = "New Chat ${conversations.size + 1}"
        )
        conversations.add(0, newConversation)
        conversationAdapter.notifyItemInserted(0)
        switchToConversation(newConversation)
        saveConversations()

        // Track that a new conversation has started
        memoryManager.incrementMessageStats(isNewConversation = true)

        Toast.makeText(this, "New chat created", Toast.LENGTH_SHORT).show()
    }

    private fun deleteConversation(conversation: Conversation, position: Int) {
        conversations.removeAt(position)
        conversationAdapter.notifyItemRemoved(position)
        saveConversations()
        
        if (currentConversation == conversation) {
            if (conversations.isNotEmpty()) {
                switchToConversation(conversations[0])
            } else {
                createNewConversation()
            }
        }
        
        Toast.makeText(this, "Chat deleted", Toast.LENGTH_SHORT).show()
    }

    private fun switchToConversation(conversation: Conversation) {
        currentConversation = conversation
        
        chatAdapter.clearMessages()
        conversation.messages.forEach { message ->
            chatAdapter.addMessage(message)
        }
        
        val position = conversations.indexOf(conversation)
        conversationAdapter.setSelected(position)
        
        scrollToBottom()
    }

    private fun scrollToBottom() {
        rvChatMessages.post {
            if (chatAdapter.itemCount > 0) {
                rvChatMessages.smoothScrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }

    private fun loadConversations() {
        try {
            val json = prefs.getString("conversations", null)
            if (json != null) {
                val type = object : TypeToken<List<Conversation>>() {}.type
                val loaded = gson.fromJson<List<Conversation>>(json, type)
                conversations.clear()
                conversations.addAll(loaded)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveConversations() {
        try {
            val json = gson.toJson(conversations)
            prefs.edit().putString("conversations", json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        saveConversations()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(imageReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}
