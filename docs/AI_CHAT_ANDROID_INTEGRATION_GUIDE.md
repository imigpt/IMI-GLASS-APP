# AI Chat Feature - Android Integration Guide

**Version**: 1.0  
**Created**: 2026-06-18  
**Target Platform**: Android (Kotlin/Java)

---

## Table of Contents

1. [API Client Setup](#1-api-client-setup)
2. [Data Models](#2-data-models)
3. [ViewModels & State Management](#3-viewmodels--state-management)
4. [UI Implementation](#4-ui-implementation)
5. [Local Storage (Room Database)](#5-local-storage-room-database)
6. [Error Handling](#6-error-handling)
7. [Security Considerations](#7-security-considerations)

---

## 1. API Client Setup

### 1.1 Dependencies (build.gradle.kts)

```kotlin
dependencies {
    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")

    // Jetpack
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.room:room-runtime:2.5.2")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Dependency Injection
    implementation("io.insert-koin:koin-android:3.4.0")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Logging
    implementation("com.orhanobut:logger:2.2.0")

    // Encryption
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
}
```

### 1.2 Retrofit API Service

```kotlin
// com/example/aichat/api/ChatApiService.kt
import retrofit2.Response
import retrofit2.http.*

interface ChatApiService {
    // Auth Endpoints
    @POST("api/v1/auth/register")
    suspend fun registerUser(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("api/v1/auth/login")
    suspend fun loginUser(@Body request: LoginRequest): Response<LoginResponse>

    @POST("api/v1/auth/refresh-token")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): Response<TokenResponse>

    // Chat Session Endpoints
    @POST("api/v1/chat/sessions")
    suspend fun createSession(
        @Body request: CreateSessionRequest,
        @Header("Authorization") token: String
    ): Response<CreateSessionResponse>

    @GET("api/v1/chat/sessions")
    suspend fun getSessions(
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 10
    ): Response<GetSessionsResponse>

    @GET("api/v1/chat/sessions/{sessionId}")
    suspend fun getSession(
        @Path("sessionId") sessionId: String,
        @Header("Authorization") token: String
    ): Response<ChatSession>

    @PUT("api/v1/chat/sessions/{sessionId}")
    suspend fun updateSession(
        @Path("sessionId") sessionId: String,
        @Body request: UpdateSessionRequest,
        @Header("Authorization") token: String
    ): Response<ChatSession>

    @POST("api/v1/chat/sessions/{sessionId}/close")
    suspend fun closeSession(
        @Path("sessionId") sessionId: String,
        @Header("Authorization") token: String
    ): Response<CloseSessionResponse>

    // Message Endpoints
    @POST("api/v1/chat/messages")
    suspend fun sendMessage(
        @Body request: SendMessageRequest,
        @Header("Authorization") token: String
    ): Response<SendMessageResponse>

    @GET("api/v1/chat/messages/{sessionId}")
    suspend fun getMessages(
        @Path("sessionId") sessionId: String,
        @Header("Authorization") token: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<GetMessagesResponse>

    // History & Analytics
    @GET("api/v1/chat/history")
    suspend fun getChatHistory(
        @Header("Authorization") token: String
    ): Response<GetHistoryResponse>

    @GET("api/v1/chat/analytics")
    suspend fun getAnalytics(
        @Header("Authorization") token: String
    ): Response<AnalyticsResponse>
}
```

### 1.3 Retrofit Configuration

```kotlin
// com/example/aichat/api/ApiClient.kt
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val BASE_URL = "https://api.yourdomain.com/"
    private const val TIMEOUT_SECONDS = 30L

    fun createApiService(accessToken: String = ""): ChatApiService {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()

                if (accessToken.isNotEmpty()) {
                    requestBuilder.addHeader("Authorization", "Bearer $accessToken")
                }

                requestBuilder.addHeader("Content-Type", "application/json")
                chain.proceed(requestBuilder.build())
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(ChatApiService::class.java)
    }
}
```

---

## 2. Data Models

### 2.1 Request Models

```kotlin
// com/example/aichat/data/models/Requests.kt

data class RegisterRequest(
    val email: String,
    val username: String,
    val password: String,
    val full_name: String,
    val phone_number: String? = null
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class RefreshTokenRequest(
    val refresh_token: String
)

data class CreateSessionRequest(
    val session_name: String,
    val ai_teacher_id: String = "gemini-pro",
    val topic: String? = null
)

data class UpdateSessionRequest(
    val session_name: String? = null,
    val is_active: Boolean? = null
)

data class SendMessageRequest(
    val session_id: String,
    val content: String,
    val message_type: String = "user_message"
)
```

### 2.2 Response Models

```kotlin
// com/example/aichat/data/models/Responses.kt

data class RegisterResponse(
    val success: Boolean,
    val message: String,
    val data: RegisterData
)

data class RegisterData(
    val user_id: String,
    val email: String,
    val username: String,
    val token: String
)

data class LoginResponse(
    val success: Boolean,
    val data: LoginData
)

data class LoginData(
    val user_id: String,
    val token: String,
    val refresh_token: String,
    val expires_in: Long,
    val user: UserInfo
)

data class UserInfo(
    val id: String,
    val email: String,
    val username: String,
    val full_name: String? = null
)

data class TokenResponse(
    val success: Boolean,
    val data: TokenData
)

data class TokenData(
    val token: String,
    val refresh_token: String,
    val expires_in: Long
)

data class CreateSessionResponse(
    val success: Boolean,
    val data: ChatSession
)

data class ChatSession(
    val session_id: String,
    val user_id: String,
    val session_name: String,
    val ai_teacher_id: String? = null,
    val created_at: String,
    val updated_at: String? = null,
    val total_messages: Int = 0,
    val is_active: Boolean = true
)

data class GetSessionsResponse(
    val success: Boolean,
    val data: List<ChatSession>,
    val pagination: Pagination
)

data class Pagination(
    val total: Int,
    val page: Int,
    val limit: Int
)

data class CloseSessionResponse(
    val success: Boolean,
    val data: SessionCloseData
)

data class SessionCloseData(
    val session_id: String,
    val is_active: Boolean,
    val closed_at: String,
    val session_duration_seconds: Int
)

data class SendMessageResponse(
    val success: Boolean,
    val data: MessagePair
)

data class MessagePair(
    val userMessage: ChatMessage,
    val aiResponse: ChatMessage
)

data class ChatMessage(
    val message_id: String,
    val session_id: String? = null,
    val user_id: String? = null,
    val message_type: String,
    val content: String,
    val created_at: String,
    val metadata: MessageMetadata? = null
)

data class MessageMetadata(
    val model: String? = null,
    val confidence_score: Double? = null,
    val processing_time_ms: Int? = null
)

data class GetMessagesResponse(
    val success: Boolean,
    val data: List<ChatMessage>,
    val pagination: Pagination
)

data class GetHistoryResponse(
    val success: Boolean,
    val data: List<HistoryItem>
)

data class HistoryItem(
    val session_id: String,
    val session_name: String,
    val total_user_messages: Int,
    val total_ai_responses: Int,
    val conversation_duration_seconds: Int,
    val session_start_time: String,
    val session_end_time: String? = null
)

data class AnalyticsResponse(
    val success: Boolean,
    val data: AnalyticsData
)

data class AnalyticsData(
    val total_sessions: Int,
    val total_messages_sent: Int,
    val total_ai_responses: Int,
    val average_session_duration: Int,
    val most_active_topic: String? = null,
    val last_active: String? = null
)

data class ApiError(
    val success: Boolean,
    val error: ErrorDetail
)

data class ErrorDetail(
    val code: String,
    val message: String,
    val details: List<Map<String, String>>? = null
)
```

---

## 3. ViewModels & State Management

### 3.1 Authentication ViewModel

```kotlin
// com/example/aichat/ui/viewmodels/AuthViewModel.kt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AuthState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val user: UserInfo? = null,
    val error: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null
)

class AuthViewModel(
    private val apiService: ChatApiService,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState

    fun register(
        email: String,
        username: String,
        password: String,
        fullName: String
    ) = viewModelScope.launch {
        _authState.value = _authState.value.copy(isLoading = true, error = null)

        try {
            val request = RegisterRequest(
                email = email,
                username = username,
                password = password,
                full_name = fullName
            )

            val response = apiService.registerUser(request)

            if (response.isSuccessful && response.body()?.success == true) {
                val data = response.body()!!.data
                tokenManager.saveToken(data.token)

                _authState.value = _authState.value.copy(
                    isLoading = false,
                    isAuthenticated = true,
                    accessToken = data.token,
                    error = null
                )
            } else {
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    error = response.body()?.message ?: "Registration failed"
                )
            }
        } catch (e: Exception) {
            _authState.value = _authState.value.copy(
                isLoading = false,
                error = e.message ?: "An error occurred"
            )
        }
    }

    fun login(email: String, password: String) = viewModelScope.launch {
        _authState.value = _authState.value.copy(isLoading = true, error = null)

        try {
            val request = LoginRequest(email = email, password = password)
            val response = apiService.loginUser(request)

            if (response.isSuccessful && response.body()?.success == true) {
                val data = response.body()!!.data
                tokenManager.saveToken(data.token)
                tokenManager.saveRefreshToken(data.refresh_token)

                _authState.value = _authState.value.copy(
                    isLoading = false,
                    isAuthenticated = true,
                    user = data.user,
                    accessToken = data.token,
                    refreshToken = data.refresh_token,
                    error = null
                )
            } else {
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    error = "Invalid credentials"
                )
            }
        } catch (e: Exception) {
            _authState.value = _authState.value.copy(
                isLoading = false,
                error = e.message ?: "Login failed"
            )
        }
    }

    fun logout() = viewModelScope.launch {
        tokenManager.clearTokens()
        _authState.value = AuthState()
    }
}
```

### 3.2 Chat ViewModel

```kotlin
// com/example/aichat/ui/viewmodels/ChatViewModel.kt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ChatState(
    val isLoading: Boolean = false,
    val sessions: List<ChatSession> = emptyList(),
    val currentSession: ChatSession? = null,
    val messages: List<ChatMessage> = emptyList(),
    val error: String? = null,
    val isSendingMessage: Boolean = false
)

class ChatViewModel(
    private val apiService: ChatApiService,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _chatState = MutableStateFlow(ChatState())
    val chatState: StateFlow<ChatState> = _chatState

    fun createSession(sessionName: String, topic: String? = null) = viewModelScope.launch {
        _chatState.value = _chatState.value.copy(isLoading = true, error = null)

        try {
            val token = tokenManager.getToken() ?: throw Exception("No auth token")
            val request = CreateSessionRequest(
                session_name = sessionName,
                topic = topic
            )

            val response = apiService.createSession(request, "Bearer $token")

            if (response.isSuccessful && response.body()?.success == true) {
                val session = response.body()!!.data
                _chatState.value = _chatState.value.copy(
                    isLoading = false,
                    currentSession = session,
                    messages = emptyList(),
                    error = null
                )
            } else {
                _chatState.value = _chatState.value.copy(
                    isLoading = false,
                    error = "Failed to create session"
                )
            }
        } catch (e: Exception) {
            _chatState.value = _chatState.value.copy(
                isLoading = false,
                error = e.message ?: "An error occurred"
            )
        }
    }

    fun loadSessions() = viewModelScope.launch {
        _chatState.value = _chatState.value.copy(isLoading = true, error = null)

        try {
            val token = tokenManager.getToken() ?: throw Exception("No auth token")
            val response = apiService.getSessions("Bearer $token")

            if (response.isSuccessful && response.body()?.success == true) {
                val sessions = response.body()!!.data
                _chatState.value = _chatState.value.copy(
                    isLoading = false,
                    sessions = sessions,
                    error = null
                )
            } else {
                _chatState.value = _chatState.value.copy(
                    isLoading = false,
                    error = "Failed to load sessions"
                )
            }
        } catch (e: Exception) {
            _chatState.value = _chatState.value.copy(
                isLoading = false,
                error = e.message ?: "An error occurred"
            )
        }
    }

    fun loadMessages(sessionId: String) = viewModelScope.launch {
        _chatState.value = _chatState.value.copy(isLoading = true, error = null)

        try {
            val token = tokenManager.getToken() ?: throw Exception("No auth token")
            val response = apiService.getMessages(sessionId, "Bearer $token")

            if (response.isSuccessful && response.body()?.success == true) {
                val messages = response.body()!!.data
                _chatState.value = _chatState.value.copy(
                    isLoading = false,
                    messages = messages,
                    error = null
                )
            } else {
                _chatState.value = _chatState.value.copy(
                    isLoading = false,
                    error = "Failed to load messages"
                )
            }
        } catch (e: Exception) {
            _chatState.value = _chatState.value.copy(
                isLoading = false,
                error = e.message ?: "An error occurred"
            )
        }
    }

    fun sendMessage(sessionId: String, content: String) = viewModelScope.launch {
        _chatState.value = _chatState.value.copy(isSendingMessage = true, error = null)

        try {
            val token = tokenManager.getToken() ?: throw Exception("No auth token")
            val request = SendMessageRequest(
                session_id = sessionId,
                content = content
            )

            val response = apiService.sendMessage(request, "Bearer $token")

            if (response.isSuccessful && response.body()?.success == true) {
                val messagePair = response.body()!!.data
                val updatedMessages = _chatState.value.messages.toMutableList().apply {
                    add(messagePair.userMessage)
                    add(messagePair.aiResponse)
                }

                _chatState.value = _chatState.value.copy(
                    isSendingMessage = false,
                    messages = updatedMessages,
                    error = null
                )
            } else {
                _chatState.value = _chatState.value.copy(
                    isSendingMessage = false,
                    error = "Failed to send message"
                )
            }
        } catch (e: Exception) {
            _chatState.value = _chatState.value.copy(
                isSendingMessage = false,
                error = e.message ?: "Failed to send message"
            )
        }
    }

    fun closeSession(sessionId: String) = viewModelScope.launch {
        _chatState.value = _chatState.value.copy(isLoading = true)

        try {
            val token = tokenManager.getToken() ?: throw Exception("No auth token")
            val response = apiService.closeSession(sessionId, "Bearer $token")

            if (response.isSuccessful && response.body()?.success == true) {
                _chatState.value = _chatState.value.copy(
                    isLoading = false,
                    currentSession = null,
                    messages = emptyList()
                )
            }
        } catch (e: Exception) {
            _chatState.value = _chatState.value.copy(
                isLoading = false,
                error = e.message ?: "Failed to close session"
            )
        }
    }
}
```

---

## 4. UI Implementation

### 4.1 Composable for Chat Screen (Jetpack Compose)

```kotlin
// com/example/aichat/ui/screens/ChatScreen.kt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    sessionId: String
) {
    val chatState by viewModel.chatState.collectAsState()
    var messageText by remember { mutableStateOf("") }

    LaunchedEffect(sessionId) {
        viewModel.loadMessages(sessionId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Messages List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = true
        ) {
            items(chatState.messages.size) { index ->
                val message = chatState.messages[index]
                ChatMessageItem(
                    message = message,
                    isUserMessage = message.message_type == "user_message"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Error Message
        if (chatState.error != null) {
            Text(
                text = chatState.error!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }

        // Message Input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            TextField(
                value = messageText,
                onValueChange = { messageText = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                placeholder = { Text("Type a message...") },
                enabled = !chatState.isSendingMessage
            )

            Button(
                onClick = {
                    if (messageText.isNotBlank()) {
                        viewModel.sendMessage(sessionId, messageText)
                        messageText = ""
                    }
                },
                enabled = messageText.isNotBlank() && !chatState.isSendingMessage
            ) {
                Text("Send")
            }
        }

        if (chatState.isSendingMessage) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
fun ChatMessageItem(
    message: ChatMessage,
    isUserMessage: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = if (isUserMessage) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            color = if (isUserMessage)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = message.content,
                    color = if (isUserMessage)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formatTime(message.created_at),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUserMessage)
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun formatTime(timestamp: String): String {
    // Format timestamp to readable format
    return timestamp.substring(11, 16) // HH:MM format
}
```

### 4.2 Chat Sessions Screen

```kotlin
// com/example/aichat/ui/screens/ChatSessionsScreen.kt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatSessionsScreen(
    viewModel: ChatViewModel = viewModel(),
    onSessionClick: (String) -> Unit,
    onCreateSessionClick: () -> Unit
) {
    val chatState by viewModel.chatState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadSessions()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Chat Sessions",
                style = MaterialTheme.typography.headlineSmall
            )

            FloatingActionButton(
                onClick = onCreateSessionClick,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, "Create Session")
            }
        }

        if (chatState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // Sessions List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                items(chatState.sessions.size) { index ->
                    SessionItem(
                        session = chatState.sessions[index],
                        onClick = { onSessionClick(chatState.sessions[index].session_id) }
                    )
                }
            }
        }

        // Error Message
        if (chatState.error != null) {
            Text(
                text = chatState.error!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
fun SessionItem(
    session: ChatSession,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = session.session_name,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Messages: ${session.total_messages}",
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    text = if (session.is_active) "Active" else "Closed",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (session.is_active)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
```

---

## 5. Local Storage (Room Database)

### 5.1 Room Database Setup

```kotlin
// com/example/aichat/data/db/ChatDatabase.kt
import androidx.room.*

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getDatabase(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "chat_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

### 5.2 Entity Classes

```kotlin
// com/example/aichat/data/db/Entities.kt
import androidx.room.*

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    val sessionId: String,
    val sessionName: String,
    val aiTeacherId: String,
    val createdAt: String,
    val totalMessages: Int = 0,
    val isActive: Boolean = true
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatMessageEntity(
    @PrimaryKey
    val messageId: String,
    val sessionId: String,
    val messageType: String, // "user_message" or "ai_response"
    val content: String,
    val createdAt: String
)
```

### 5.3 DAO (Data Access Object)

```kotlin
// com/example/aichat/data/db/Daos.kt
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions WHERE sessionId = :sessionId")
    fun getSessionById(sessionId: String): Flow<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Delete
    suspend fun deleteSession(session: ChatSessionEntity)
}

@Dao
interface ChatMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Delete
    suspend fun deleteMessage(message: ChatMessageEntity)
}
```

---

## 6. Error Handling

### 6.1 Error Handler

```kotlin
// com/example/aichat/utils/ErrorHandler.kt
sealed class ApiError : Exception() {
    data class ValidationError(val details: List<String>) : ApiError()
    data class UnauthorizedError(val message: String) : ApiError()
    data class NotFoundError(val message: String) : ApiError()
    data class ServerError(val message: String) : ApiError()
    data class NetworkError(val message: String) : ApiError()
    data class UnknownError(val throwable: Throwable) : ApiError()
}

object ErrorHandler {
    fun handle(response: Response<*>): ApiError {
        return when {
            !response.isSuccessful -> {
                when (response.code()) {
                    400 -> ApiError.ValidationError(emptyList())
                    401 -> ApiError.UnauthorizedError("Unauthorized")
                    404 -> ApiError.NotFoundError("Not found")
                    in 500..599 -> ApiError.ServerError("Server error")
                    else -> ApiError.ServerError("Unknown error")
                }
            }
            else -> ApiError.UnknownError(Exception("Unknown error"))
        }
    }

    fun handle(throwable: Throwable): ApiError {
        return when (throwable) {
            is java.net.UnknownHostException -> ApiError.NetworkError("No internet connection")
            is java.net.SocketTimeoutException -> ApiError.NetworkError("Request timeout")
            else -> ApiError.UnknownError(throwable)
        }
    }
}
```

### 6.2 Token Manager

```kotlin
// com/example/aichat/utils/TokenManager.kt
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveToken(token: String) {
        sharedPreferences.edit().putString("access_token", token).apply()
    }

    fun getToken(): String? {
        return sharedPreferences.getString("access_token", null)
    }

    fun saveRefreshToken(token: String) {
        sharedPreferences.edit().putString("refresh_token", token).apply()
    }

    fun getRefreshToken(): String? {
        return sharedPreferences.getString("refresh_token", null)
    }

    fun clearTokens() {
        sharedPreferences.edit().clear().apply()
    }

    fun isTokenExpired(): Boolean {
        // Implement token expiry check
        return false
    }
}
```

---

## 7. Security Considerations

### Best Practices

1. **Token Storage**: Use EncryptedSharedPreferences for storing sensitive tokens
2. **HTTPS Only**: Always use HTTPS for API communication
3. **Certificate Pinning**: Implement certificate pinning for production
4. **Input Validation**: Validate all user inputs before sending
5. **Timeout**: Set reasonable timeout values for API calls
6. **Logging**: Never log sensitive information (tokens, passwords)
7. **Encryption**: Encrypt sensitive data at rest using Android's encryption APIs

### Certificate Pinning

```kotlin
// com/example/aichat/utils/CertificatePinning.kt
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient

fun createSecureOkHttpClient(): OkHttpClient {
    val certificatePinner = CertificatePinner.Builder()
        .add("api.yourdomain.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .build()

    return OkHttpClient.Builder()
        .certificatePinner(certificatePinner)
        .build()
}
```

---

**Document Version**: 1.0  
**Created**: 2026-06-18  
**Last Modified**: 2026-06-18
