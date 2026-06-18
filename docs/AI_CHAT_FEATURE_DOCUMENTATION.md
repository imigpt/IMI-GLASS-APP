# AI Chat Feature - Complete Technical Documentation

## 1. Feature Overview

### Description
A secure AI chat system that allows users to communicate with an AI teacher/assistant. All conversations are stored in the backend database and are accessible for admin review, analytics, and user history retrieval.

### Key Features
- Real-time chat messaging with AI
- Complete conversation history storage
- Secure backend storage with encryption
- User authentication and authorization
- Chat session management
- Message persistence and retrieval
- Admin dashboard access to conversations
- User privacy controls

---

## 2. Architecture Overview

```
┌─────────────────┐
│   Android App   │
│   (Frontend)    │
└────────┬────────┘
         │
         │ HTTP/HTTPS
         ▼
┌─────────────────────────────────────────────┐
│          Backend API Server                  │
│  (Node.js/Express or Java)                   │
├─────────────────────────────────────────────┤
│  • Chat Controller/Routes                    │
│  • Authentication Middleware                 │
│  • Message Processing Logic                  │
│  • AI Integration Service                    │
└────────┬────────────────────────────────────┘
         │
         │ Database Query
         ▼
┌─────────────────────────────────────────────┐
│          Database (PostgreSQL)               │
│  • Users Table                               │
│  • Chat Sessions Table                       │
│  • Messages Table                            │
│  • AI Responses Table                        │
│  • Chat History Table                        │
└─────────────────────────────────────────────┘
         │
         │ (Optional) Message Encryption
         ▼
┌─────────────────────────────────────────────┐
│    External AI Service (Gemini/ChatGPT)     │
│    (For AI Response Generation)              │
└─────────────────────────────────────────────┘
```

---

## 3. Database Schema

### 3.1 Users Table
```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    username VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    phone_number VARCHAR(20),
    user_role ENUM('student', 'teacher', 'admin') DEFAULT 'student',
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    profile_image_url VARCHAR(500)
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_username ON users(username);
```

### 3.2 Chat Sessions Table
```sql
CREATE TABLE chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_name VARCHAR(255),
    ai_teacher_id VARCHAR(255) NOT NULL, -- AI model identifier
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_message_at TIMESTAMP,
    total_messages INT DEFAULT 0,
    duration_seconds INT DEFAULT 0
);

CREATE INDEX idx_chat_sessions_user_id ON chat_sessions(user_id);
CREATE INDEX idx_chat_sessions_created_at ON chat_sessions(created_at);
CREATE INDEX idx_chat_sessions_is_active ON chat_sessions(is_active);
```

### 3.3 Messages Table
```sql
CREATE TABLE chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message_type ENUM('user_message', 'ai_response') NOT NULL,
    content TEXT NOT NULL,
    encrypted_content TEXT, -- For security purposes
    is_encrypted BOOLEAN DEFAULT false,
    message_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP,
    flagged_for_review BOOLEAN DEFAULT false,
    metadata JSONB, -- Store additional data like attachments, formatting
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chat_messages_session_id ON chat_messages(session_id);
CREATE INDEX idx_chat_messages_user_id ON chat_messages(user_id);
CREATE INDEX idx_chat_messages_message_type ON chat_messages(message_type);
CREATE INDEX idx_chat_messages_timestamp ON chat_messages(message_timestamp);
CREATE INDEX idx_chat_messages_flagged ON chat_messages(flagged_for_review);
```

### 3.4 AI Responses Metadata Table
```sql
CREATE TABLE ai_response_metadata (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES chat_sessions(id),
    ai_model VARCHAR(100) NOT NULL, -- e.g., 'gemini-pro', 'gpt-4'
    model_version VARCHAR(50),
    confidence_score DECIMAL(3,2), -- 0.00 to 1.00
    processing_time_ms INT,
    tokens_used INT,
    api_call_id VARCHAR(255), -- For tracking external API calls
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ai_response_message_id ON ai_response_metadata(message_id);
```

### 3.5 Chat History View (for easy retrieval)
```sql
CREATE TABLE chat_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    conversation_summary TEXT,
    keywords JSONB,
    total_user_messages INT,
    total_ai_responses INT,
    conversation_duration_seconds INT,
    session_start_time TIMESTAMP,
    session_end_time TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chat_history_session_id ON chat_history(session_id);
CREATE INDEX idx_chat_history_user_id ON chat_history(user_id);
```

### 3.6 Chat Audit Log Table (for security & compliance)
```sql
CREATE TABLE chat_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES chat_sessions(id),
    user_id UUID NOT NULL REFERENCES users(id),
    action VARCHAR(100) NOT NULL, -- e.g., 'message_sent', 'session_opened', 'session_closed'
    action_details JSONB,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    status VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_log_user_id ON chat_audit_log(user_id);
CREATE INDEX idx_audit_log_created_at ON chat_audit_log(created_at);
```

---

## 4. API Endpoints

### 4.1 Authentication Endpoints

#### POST `/api/v1/auth/register`
Register a new user
```json
Request:
{
    "email": "student@example.com",
    "username": "student123",
    "password": "securepassword123",
    "full_name": "John Doe",
    "phone_number": "+1234567890"
}

Response (201):
{
    "success": true,
    "message": "User registered successfully",
    "data": {
        "user_id": "uuid",
        "email": "student@example.com",
        "username": "student123",
        "token": "jwt_token_here"
    }
}
```

#### POST `/api/v1/auth/login`
User login
```json
Request:
{
    "email": "student@example.com",
    "password": "securepassword123"
}

Response (200):
{
    "success": true,
    "data": {
        "user_id": "uuid",
        "token": "jwt_token_here",
        "expires_in": 86400,
        "user": {
            "id": "uuid",
            "email": "student@example.com",
            "username": "student123"
        }
    }
}
```

#### POST `/api/v1/auth/refresh-token`
Refresh JWT token
```json
Request:
{
    "refresh_token": "refresh_token_here"
}

Response (200):
{
    "success": true,
    "data": {
        "token": "new_jwt_token",
        "expires_in": 86400
    }
}
```

---

### 4.2 Chat Session Endpoints

#### POST `/api/v1/chat/sessions`
Create a new chat session
```json
Request:
{
    "session_name": "Math Lesson",
    "ai_teacher_id": "gemini-pro",
    "topic": "Algebra Basics"
}

Response (201):
{
    "success": true,
    "data": {
        "session_id": "uuid",
        "user_id": "uuid",
        "session_name": "Math Lesson",
        "created_at": "2026-06-18T10:30:00Z",
        "is_active": true
    }
}
```

#### GET `/api/v1/chat/sessions`
Get all chat sessions for the current user
```json
Response (200):
{
    "success": true,
    "data": [
        {
            "session_id": "uuid",
            "session_name": "Math Lesson",
            "created_at": "2026-06-18T10:30:00Z",
            "last_message_at": "2026-06-18T10:45:00Z",
            "total_messages": 15,
            "is_active": true
        }
    ],
    "pagination": {
        "total": 10,
        "page": 1,
        "limit": 10
    }
}
```

#### GET `/api/v1/chat/sessions/{sessionId}`
Get details of a specific chat session
```json
Response (200):
{
    "success": true,
    "data": {
        "session_id": "uuid",
        "user_id": "uuid",
        "session_name": "Math Lesson",
        "ai_teacher_id": "gemini-pro",
        "created_at": "2026-06-18T10:30:00Z",
        "updated_at": "2026-06-18T10:45:00Z",
        "total_messages": 15,
        "is_active": true
    }
}
```

#### PUT `/api/v1/chat/sessions/{sessionId}`
Update a chat session
```json
Request:
{
    "session_name": "Advanced Math Lesson",
    "is_active": true
}

Response (200):
{
    "success": true,
    "data": {
        "session_id": "uuid",
        "session_name": "Advanced Math Lesson",
        "updated_at": "2026-06-18T10:50:00Z"
    }
}
```

#### POST `/api/v1/chat/sessions/{sessionId}/close`
Close/end a chat session
```json
Response (200):
{
    "success": true,
    "data": {
        "session_id": "uuid",
        "is_active": false,
        "closed_at": "2026-06-18T10:55:00Z",
        "session_duration_seconds": 1500
    }
}
```

---

### 4.3 Chat Message Endpoints

#### POST `/api/v1/chat/messages`
Send a message to AI teacher
```json
Request:
{
    "session_id": "uuid",
    "content": "What is the formula for calculating the area of a circle?",
    "message_type": "user_message"
}

Response (201):
{
    "success": true,
    "data": {
        "message_id": "uuid",
        "session_id": "uuid",
        "user_id": "uuid",
        "message_type": "user_message",
        "content": "What is the formula for calculating the area of a circle?",
        "created_at": "2026-06-18T10:35:00Z",
        "ai_response": {
            "message_id": "uuid",
            "content": "The formula for the area of a circle is A = πr², where...",
            "created_at": "2026-06-18T10:35:05Z",
            "metadata": {
                "model": "gemini-pro",
                "processing_time_ms": 245,
                "confidence_score": 0.95
            }
        }
    }
}
```

#### GET `/api/v1/chat/messages/{sessionId}`
Get all messages in a chat session
```json
Response (200):
{
    "success": true,
    "data": [
        {
            "message_id": "uuid",
            "message_type": "user_message",
            "content": "What is the formula for calculating the area of a circle?",
            "created_at": "2026-06-18T10:35:00Z"
        },
        {
            "message_id": "uuid",
            "message_type": "ai_response",
            "content": "The formula for the area of a circle is A = πr²...",
            "created_at": "2026-06-18T10:35:05Z",
            "metadata": {
                "model": "gemini-pro",
                "confidence_score": 0.95
            }
        }
    ],
    "pagination": {
        "total": 30,
        "page": 1,
        "limit": 20
    }
}
```

#### GET `/api/v1/chat/messages/{messageId}`
Get a specific message
```json
Response (200):
{
    "success": true,
    "data": {
        "message_id": "uuid",
        "session_id": "uuid",
        "message_type": "user_message",
        "content": "What is the formula for calculating the area of a circle?",
        "created_at": "2026-06-18T10:35:00Z"
    }
}
```

#### DELETE `/api/v1/chat/messages/{messageId}`
Delete a message (soft delete)
```json
Response (200):
{
    "success": true,
    "message": "Message deleted successfully"
}
```

---

### 4.4 Chat History & Analytics Endpoints

#### GET `/api/v1/chat/history`
Get user's complete chat history
```json
Response (200):
{
    "success": true,
    "data": [
        {
            "session_id": "uuid",
            "session_name": "Math Lesson",
            "total_user_messages": 10,
            "total_ai_responses": 10,
            "conversation_duration_seconds": 1500,
            "session_start_time": "2026-06-18T10:30:00Z",
            "session_end_time": "2026-06-18T10:55:00Z"
        }
    ]
}
```

#### GET `/api/v1/chat/analytics`
Get chat analytics for a user
```json
Response (200):
{
    "success": true,
    "data": {
        "total_sessions": 25,
        "total_messages_sent": 150,
        "total_ai_responses": 150,
        "average_session_duration": 1200,
        "most_active_topic": "Mathematics",
        "last_active": "2026-06-18T10:55:00Z"
    }
}
```

---

### 4.5 Admin Endpoints

#### GET `/api/v1/admin/chat/sessions`
Get all chat sessions (admin only)
```json
Query Parameters:
- page: int (default 1)
- limit: int (default 20)
- user_id: uuid (optional filter)
- date_from: ISO8601 (optional)
- date_to: ISO8601 (optional)

Response (200):
{
    "success": true,
    "data": [
        {
            "session_id": "uuid",
            "user_id": "uuid",
            "user_email": "student@example.com",
            "session_name": "Math Lesson",
            "created_at": "2026-06-18T10:30:00Z",
            "total_messages": 15
        }
    ],
    "pagination": {
        "total": 500,
        "page": 1,
        "limit": 20
    }
}
```

#### GET `/api/v1/admin/chat/sessions/{sessionId}/messages`
Get all messages for a session (admin only)
```json
Response (200):
{
    "success": true,
    "data": [
        {
            "message_id": "uuid",
            "user_id": "uuid",
            "message_type": "user_message",
            "content": "...",
            "created_at": "2026-06-18T10:35:00Z"
        }
    ]
}
```

#### POST `/api/v1/admin/chat/messages/{messageId}/flag`
Flag a message for review (admin only)
```json
Request:
{
    "reason": "Inappropriate content",
    "severity": "high"
}

Response (200):
{
    "success": true,
    "message": "Message flagged for review"
}
```

#### GET `/api/v1/admin/chat/audit-log`
Get audit log (admin only)
```json
Query Parameters:
- user_id: uuid
- action: string
- date_from: ISO8601
- date_to: ISO8601

Response (200):
{
    "success": true,
    "data": [
        {
            "id": "uuid",
            "user_id": "uuid",
            "action": "message_sent",
            "created_at": "2026-06-18T10:35:00Z"
        }
    ]
}
```

---

## 5. Security Specifications

### 5.1 Authentication & Authorization
- **JWT Token-based Authentication**: Use JWT (JSON Web Token) for stateless authentication
- **Token Expiry**: 24 hours for access token, 7 days for refresh token
- **HTTPS Only**: All API calls must use HTTPS
- **Password Security**: Bcrypt hashing with salt rounds = 12
- **Rate Limiting**: Max 100 requests per minute per user

### 5.2 Data Encryption
```
- At Rest: AES-256-GCM encryption for sensitive data
- In Transit: TLS 1.2 or higher
- Database: Encrypted storage for chat content (optional field)
- Field-level encryption for: message content, user PII
```

### 5.3 Authorization Levels
```
- Student: Can access own sessions and messages only
- Teacher: Can access student sessions assigned to them
- Admin: Full access to all sessions, messages, and audit logs
```

### 5.4 Data Privacy
- Users can request data export
- Users can request data deletion
- GDPR compliance for EU users
- Session encryption at database level
- PII fields encrypted in database

### 5.5 API Security Headers
```
- X-Content-Type-Options: nosniff
- X-Frame-Options: DENY
- X-XSS-Protection: 1; mode=block
- Strict-Transport-Security: max-age=31536000
- Content-Security-Policy: appropriate policies
```

---

## 6. Error Handling

### Standard Error Response Format
```json
{
    "success": false,
    "error": {
        "code": "VALIDATION_ERROR",
        "message": "Invalid request parameters",
        "details": [
            {
                "field": "content",
                "message": "Content cannot be empty"
            }
        ]
    }
}
```

### HTTP Status Codes
| Status | Meaning |
|--------|---------|
| 200 | OK - Request successful |
| 201 | Created - Resource created |
| 400 | Bad Request - Invalid input |
| 401 | Unauthorized - Authentication required |
| 403 | Forbidden - Permission denied |
| 404 | Not Found - Resource not found |
| 429 | Too Many Requests - Rate limit exceeded |
| 500 | Internal Server Error |
| 503 | Service Unavailable |

### Common Error Codes
```
- INVALID_TOKEN: JWT token is invalid or expired
- UNAUTHORIZED: User not authenticated
- FORBIDDEN: User lacks permission
- SESSION_NOT_FOUND: Chat session doesn't exist
- MESSAGE_NOT_FOUND: Message doesn't exist
- DUPLICATE_SESSION: Session name already exists
- AI_SERVICE_UNAVAILABLE: External AI service down
- RATE_LIMIT_EXCEEDED: Too many requests
- INTERNAL_ERROR: Unexpected server error
```

---

## 7. Implementation Details

### 7.1 Backend Stack (Node.js/Express Recommendation)
```
- Runtime: Node.js 18+
- Framework: Express.js v4.18+
- Database: PostgreSQL 14+
- Authentication: jsonwebtoken (JWT)
- Encryption: crypto, bcryptjs
- Validation: joi or express-validator
- Logging: winston or morgan
- Error Handling: Custom error middleware
- AI Integration: Gemini SDK or OpenAI SDK
```

### 7.2 Core Service Structure
```
src/
├── config/
│   ├── database.js
│   ├── auth.js
│   └── env.js
├── controllers/
│   ├── authController.js
│   ├── chatSessionController.js
│   ├── messageController.js
│   └── adminController.js
├── services/
│   ├── authService.js
│   ├── chatService.js
│   ├── aiService.js (External AI integration)
│   ├── encryptionService.js
│   └── auditService.js
├── middleware/
│   ├── authMiddleware.js
│   ├── errorHandler.js
│   ├── validator.js
│   └── auditLogger.js
├── models/
│   ├── User.js
│   ├── ChatSession.js
│   ├── Message.js
│   └── AuditLog.js
├── routes/
│   ├── auth.js
│   ├── chat.js
│   └── admin.js
├── utils/
│   ├── encryption.js
│   ├── validators.js
│   └── helpers.js
└── app.js
```

### 7.3 Message Flow
```
1. User sends message via Android app
   ↓
2. Request hits authMiddleware (validate JWT)
   ↓
3. Request hits validator middleware
   ↓
4. messageController.sendMessage() called
   ↓
5. Save user message to database (chat_messages table)
   ↓
6. Call aiService.generateResponse() to AI
   ↓
7. Save AI response to database
   ↓
8. Create entry in ai_response_metadata
   ↓
9. Update chat_sessions (last_message_at, total_messages)
   ↓
10. Log action to chat_audit_log
    ↓
11. Return response to Android app
```

### 7.4 Android App Integration
```kotlin
// Example Kotlin code for Android integration
class ChatViewModel : ViewModel() {
    private val apiService = ApiService()
    
    fun sendMessage(sessionId: String, content: String) {
        viewModelScope.launch {
            try {
                val request = MessageRequest(sessionId, content)
                val response = apiService.sendMessage(request)
                // Update UI with response
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
    
    fun loadChatHistory(sessionId: String) {
        viewModelScope.launch {
            val messages = apiService.getMessages(sessionId)
            // Display messages
        }
    }
}
```

---

## 8. Database Deployment & Migration

### 8.1 Initial Setup Script
```sql
-- Run migrations in order
-- 1. Create ENUM types
CREATE TYPE user_role_enum AS ENUM ('student', 'teacher', 'admin');
CREATE TYPE message_type_enum AS ENUM ('user_message', 'ai_response');

-- 2. Create tables (as shown in section 3)

-- 3. Create indexes (as shown in section 3)

-- 4. Create views for easier querying
CREATE VIEW user_chat_statistics AS
SELECT 
    u.id,
    u.email,
    COUNT(DISTINCT cs.id) as total_sessions,
    COUNT(DISTINCT cm.id) as total_messages,
    MAX(cm.created_at) as last_active
FROM users u
LEFT JOIN chat_sessions cs ON u.id = cs.user_id
LEFT JOIN chat_messages cm ON cs.id = cm.session_id
GROUP BY u.id, u.email;
```

### 8.2 Backup Strategy
```
- Daily automated backups
- Point-in-time recovery enabled
- Backup retention: 30 days minimum
- Off-site backup storage
- Monthly backup restoration testing
```

---

## 9. Monitoring & Logging

### 9.1 Key Metrics to Monitor
```
- API Response time (target: < 200ms)
- Database query time (target: < 100ms)
- Error rate (target: < 0.1%)
- AI service availability (target: > 99.9%)
- Active users per hour
- Messages per hour
- Session duration
```

### 9.2 Logging Strategy
```
- Application logs: Winston (info, warn, error levels)
- Database query logs: PostgreSQL logging
- API request logs: Morgan or similar
- Audit logs: chat_audit_log table
- Error tracking: Sentry or similar
- Performance monitoring: New Relic or DataDog
```

### 9.3 Log Retention
```
- Application logs: 30 days
- Audit logs: 1 year
- Error logs: 90 days
- Database logs: 30 days
```

---

## 10. Testing Strategy

### 10.1 Unit Tests
```javascript
// Example Jest test
describe('Chat Service', () => {
    test('Should send message and get AI response', async () => {
        const result = await chatService.sendMessage(
            sessionId,
            'Test message'
        );
        expect(result.aiResponse).toBeDefined();
        expect(result.messageId).toBeDefined();
    });
});
```

### 10.2 Integration Tests
```javascript
// Test API endpoints with real database
describe('Chat API', () => {
    test('POST /api/v1/chat/messages - should create message', async () => {
        const response = await request(app)
            .post('/api/v1/chat/messages')
            .set('Authorization', `Bearer ${token}`)
            .send({
                session_id: sessionId,
                content: 'Test'
            });
        
        expect(response.status).toBe(201);
        expect(response.body.data.message_id).toBeDefined();
    });
});
```

### 10.3 Security Tests
```
- JWT validation tests
- SQL injection prevention tests
- XSS attack prevention tests
- Rate limiting tests
- Authorization/permission tests
- Data encryption verification tests
```

---

## 11. Deployment Checklist

### Pre-Deployment
- [ ] All tests passing (unit & integration)
- [ ] Code review completed
- [ ] Security review completed
- [ ] Database migrations tested
- [ ] Environment variables configured
- [ ] SSL certificates installed
- [ ] Rate limiting configured

### Deployment
- [ ] Database migrations run
- [ ] Application deployed
- [ ] Health check endpoints verified
- [ ] Load balancer configured
- [ ] Monitoring alerts set up
- [ ] Logging verified

### Post-Deployment
- [ ] Smoke tests run
- [ ] Admin endpoints accessible
- [ ] User endpoints functioning
- [ ] Logs being written correctly
- [ ] Metrics being collected
- [ ] Alerts working

---

## 12. Future Enhancements

1. **Real-time Chat**: WebSocket implementation for real-time messaging
2. **Rich Media Support**: Images, documents, voice messages
3. **Conversation Summarization**: Auto-summarize long conversations
4. **Keyword Extraction**: Extract learning topics from conversations
5. **Analytics Dashboard**: Real-time analytics for admins
6. **Multi-language Support**: Translate conversations
7. **Sentiment Analysis**: Monitor user satisfaction
8. **AI Model Switching**: Allow users to choose AI models
9. **Export Conversations**: PDF/Word export functionality
10. **Mobile App Enhancement**: Offline message queueing

---

## 13. Support & Documentation Links

- API Documentation Swagger: `/api/docs`
- Database Schema Diagram: `/docs/database-schema.png`
- Architecture Diagram: `/docs/architecture.png`
- Deployment Guide: `/docs/DEPLOYMENT.md`
- Security Policy: `/docs/SECURITY.md`
- Contributing Guide: `/docs/CONTRIBUTING.md`

---

## 14. Contact & Escalation

- **Backend Team Lead**: [Name & Email]
- **DevOps Engineer**: [Name & Email]
- **Security Officer**: [Name & Email]
- **Product Manager**: [Name & Email]

---

**Document Version**: 1.0  
**Last Updated**: 2026-06-18  
**Next Review Date**: 2026-09-18
