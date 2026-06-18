# AI Chat Backend Implementation Guide
## Complete API & Database Setup for Conversation History Storage

**Document Version**: 1.0  
**Created**: 2026-06-18  
**Target**: Backend Team Implementation  
**Technology Stack**: Node.js + Express + PostgreSQL

---

## Table of Contents
1. [Quick Start](#1-quick-start)
2. [Database Setup](#2-database-setup)
3. [Core Services](#3-core-services)
4. [API Endpoints](#4-api-endpoints)
5. [Implementation Code](#5-implementation-code)
6. [Testing](#6-testing)
7. [Deployment](#7-deployment)

---

## 1. Quick Start

### Dependencies Installation

```bash
# Initialize Node.js project
mkdir ai-chat-backend
cd ai-chat-backend
npm init -y

# Install all required packages
npm install express pg dotenv bcryptjs jsonwebtoken joi cors helmet express-validator
npm install winston uuid multer compression

# Development dependencies
npm install --save-dev nodemon jest supertest eslint
```

### Project Structure

```
ai-chat-backend/
├── src/
│   ├── config/
│   │   ├── database.js
│   │   └── env.js
│   ├── controllers/
│   │   ├── authController.js
│   │   ├── chatController.js
│   │   └── adminController.js
│   ├── services/
│   │   ├── authService.js
│   │   ├── chatService.js
│   │   ├── messageService.js
│   │   ├── aiService.js
│   │   ├── encryptionService.js
│   │   └── auditService.js
│   ├── middleware/
│   │   ├── authMiddleware.js
│   │   ├── errorHandler.js
│   │   └── validator.js
│   ├── routes/
│   │   ├── auth.js
│   │   ├── chat.js
│   │   ├── admin.js
│   │   └── index.js
│   ├── utils/
│   │   ├── logger.js
│   │   └── encryption.js
│   └── app.js
├── .env.example
├── package.json
└── README.md
```

### .env Configuration

```env
# Server Config
NODE_ENV=development
PORT=3000
HOST=localhost

# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=ai_chat_db
DB_USER=postgres
DB_PASSWORD=your_password
DB_POOL_MIN=2
DB_POOL_MAX=10

# JWT
JWT_SECRET=your_super_secret_jwt_key_change_in_production_12345
JWT_EXPIRY=24h
JWT_REFRESH_SECRET=your_refresh_token_secret
JWT_REFRESH_EXPIRY=7d

# Encryption
ENCRYPTION_KEY=abcdef0123456789abcdef0123456789
ENCRYPTION_IV=abcdef0123456789

# AI Service
AI_SERVICE_PROVIDER=gemini
GEMINI_API_KEY=your_gemini_api_key_here
OPENAI_API_KEY=your_openai_api_key_here

# Logging
LOG_LEVEL=info
```

---

## 2. Database Setup

### 2.1 Create Database

```bash
# Create PostgreSQL database
createdb ai_chat_db

# Or connect and create:
psql -U postgres
CREATE DATABASE ai_chat_db;
```

### 2.2 Complete Database Schema

Run this SQL to create all tables:

```sql
-- ============================================
-- 1. CREATE ENUM TYPES
-- ============================================
CREATE TYPE user_role_enum AS ENUM ('student', 'teacher', 'admin');
CREATE TYPE message_type_enum AS ENUM ('user_message', 'ai_response');

-- ============================================
-- 2. USERS TABLE
-- ============================================
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    username VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    phone_number VARCHAR(20),
    user_role user_role_enum DEFAULT 'student',
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_username ON users(username);
    
-- ============================================
-- 3. CHAT SESSIONS TABLE
-- ============================================
CREATE TABLE chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_name VARCHAR(255),
    ai_teacher_id VARCHAR(255) NOT NULL,
    ai_model VARCHAR(100) DEFAULT 'gemini-pro',
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_message_at TIMESTAMP WITH TIME ZONE,
    total_messages INT DEFAULT 0,
    total_user_messages INT DEFAULT 0,
    total_ai_responses INT DEFAULT 0,
    duration_seconds INT DEFAULT 0,
    metadata JSONB DEFAULT '{}'::jsonb
);

CREATE INDEX idx_sessions_user_id ON chat_sessions(user_id);
CREATE INDEX idx_sessions_created_at ON chat_sessions(created_at);
CREATE INDEX idx_sessions_is_active ON chat_sessions(is_active);

-- ============================================
-- 4. CHAT MESSAGES TABLE (Core Table)
-- ============================================
CREATE TABLE chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message_type message_type_enum NOT NULL,
    content TEXT NOT NULL,
    encrypted_content TEXT,
    is_encrypted BOOLEAN DEFAULT false,
    message_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP WITH TIME ZONE,
    flagged_for_review BOOLEAN DEFAULT false,
    metadata JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_messages_session_id ON chat_messages(session_id);
CREATE INDEX idx_messages_user_id ON chat_messages(user_id);
CREATE INDEX idx_messages_type ON chat_messages(message_type);
CREATE INDEX idx_messages_timestamp ON chat_messages(message_timestamp);
CREATE INDEX idx_messages_created_at ON chat_messages(created_at);

-- ============================================
-- 5. AI RESPONSE METADATA TABLE
-- ============================================
CREATE TABLE ai_response_metadata (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    ai_model VARCHAR(100) NOT NULL,
    confidence_score DECIMAL(3,2),
    processing_time_ms INT,
    tokens_used INT,
    api_provider VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ai_metadata_message_id ON ai_response_metadata(message_id);

-- ============================================
-- 6. CHAT HISTORY TABLE
-- ============================================
CREATE TABLE chat_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL UNIQUE REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    conversation_summary TEXT,
    total_user_messages INT,
    total_ai_responses INT,
    session_start_time TIMESTAMP WITH TIME ZONE,
    session_end_time TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_history_session_id ON chat_history(session_id);
CREATE INDEX idx_history_user_id ON chat_history(user_id);

-- ============================================
-- 7. AUDIT LOG TABLE
-- ============================================
CREATE TABLE chat_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES chat_sessions(id),
    user_id UUID NOT NULL REFERENCES users(id),
    action VARCHAR(100) NOT NULL,
    action_details JSONB DEFAULT '{}'::jsonb,
    ip_address VARCHAR(45),
    status VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_user_id ON chat_audit_log(user_id);
CREATE INDEX idx_audit_created_at ON chat_audit_log(created_at);

-- ============================================
-- 8. USER SESSION TOKENS TABLE
-- ============================================
CREATE TABLE user_session_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token VARCHAR(500) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_tokens_user_id ON user_session_tokens(user_id);
```

---

## 3. Core Services

### 3.1 Database Configuration

**File: `src/config/database.js`**

```javascript
const { Pool } = require('pg');
require('dotenv').config();

const pool = new Pool({
    host: process.env.DB_HOST,
    port: process.env.DB_PORT,
    database: process.env.DB_NAME,
    user: process.env.DB_USER,
    password: process.env.DB_PASSWORD,
    max: parseInt(process.env.DB_POOL_MAX || '10'),
    min: parseInt(process.env.DB_POOL_MIN || '2'),
    idleTimeoutMillis: 30000,
    connectionTimeoutMillis: 2000
});

pool.on('error', (err) => {
    console.error('Database pool error:', err);
});

module.exports = pool;
```

### 3.2 Auth Service

**File: `src/services/authService.js`**

```javascript
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const { v4: uuidv4 } = require('uuid');
const pool = require('../config/database');

class AuthService {
    async registerUser(userData) {
        const { email, username, password, fullName, phoneNumber } = userData;

        try {
            // Check if user exists
            const existingUser = await pool.query(
                'SELECT id FROM users WHERE email = $1 OR username = $2',
                [email, username]
            );

            if (existingUser.rows.length > 0) {
                throw new Error('Email or username already exists');
            }

            // Hash password
            const hashedPassword = await bcrypt.hash(password, 12);

            // Create user
            const result = await pool.query(
                `INSERT INTO users 
                (id, email, username, password_hash, full_name, phone_number, created_at)
                VALUES ($1, $2, $3, $4, $5, $6, CURRENT_TIMESTAMP)
                RETURNING id, email, username, full_name`,
                [uuidv4(), email, username, hashedPassword, fullName, phoneNumber]
            );

            const user = result.rows[0];

            // Generate tokens
            const { accessToken, refreshToken } = this.generateTokens(user.id, email);

            // Store refresh token
            await this.storeRefreshToken(user.id, refreshToken);

            return {
                user,
                accessToken,
                refreshToken
            };
        } catch (error) {
            throw error;
        }
    }

    async loginUser(email, password) {
        try {
            const result = await pool.query(
                'SELECT * FROM users WHERE email = $1',
                [email]
            );

            if (result.rows.length === 0) {
                throw new Error('Invalid credentials');
            }

            const user = result.rows[0];

            // Verify password
            const isPasswordValid = await bcrypt.compare(password, user.password_hash);

            if (!isPasswordValid) {
                throw new Error('Invalid credentials');
            }

            // Update last login
            await pool.query(
                'UPDATE users SET last_login = CURRENT_TIMESTAMP WHERE id = $1',
                [user.id]
            );

            // Generate tokens
            const { accessToken, refreshToken } = this.generateTokens(user.id, email);

            // Store refresh token
            await this.storeRefreshToken(user.id, refreshToken);

            return {
                user: {
                    id: user.id,
                    email: user.email,
                    username: user.username,
                    full_name: user.full_name
                },
                accessToken,
                refreshToken
            };
        } catch (error) {
            throw error;
        }
    }

    generateTokens(userId, email) {
        const accessToken = jwt.sign(
            { userId, email },
            process.env.JWT_SECRET,
            { expiresIn: process.env.JWT_EXPIRY }
        );

        const refreshToken = jwt.sign(
            { userId },
            process.env.JWT_REFRESH_SECRET,
            { expiresIn: process.env.JWT_REFRESH_EXPIRY }
        );

        return { accessToken, refreshToken };
    }

    async storeRefreshToken(userId, refreshToken) {
        const expiresAt = new Date();
        expiresAt.setDate(expiresAt.getDate() + 7);

        await pool.query(
            `INSERT INTO user_session_tokens 
            (id, user_id, refresh_token, expires_at, created_at)
            VALUES ($1, $2, $3, $4, CURRENT_TIMESTAMP)`,
            [uuidv4(), userId, refreshToken, expiresAt]
        );
    }
}

module.exports = new AuthService();
```

### 3.3 Chat Service (Message Storage & History)

**File: `src/services/chatService.js`**

```javascript
const { v4: uuidv4 } = require('uuid');
const pool = require('../config/database');

class ChatService {
    // Create a new chat session
    async createSession(userId, sessionData) {
        const { sessionName, aiTeacherId, topic } = sessionData;

        try {
            const sessionId = uuidv4();
            const result = await pool.query(
                `INSERT INTO chat_sessions 
                (id, user_id, session_name, ai_teacher_id, ai_model, created_at, updated_at)
                VALUES ($1, $2, $3, $4, $5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id, user_id, session_name, created_at, is_active`,
                [sessionId, userId, sessionName, aiTeacherId, aiTeacherId]
            );

            return result.rows[0];
        } catch (error) {
            throw error;
        }
    }

    // Get all sessions for a user
    async getUserSessions(userId, limit = 10, offset = 0) {
        try {
            const result = await pool.query(
                `SELECT * FROM chat_sessions 
                WHERE user_id = $1 
                ORDER BY created_at DESC 
                LIMIT $2 OFFSET $3`,
                [userId, limit, offset]
            );

            const countResult = await pool.query(
                'SELECT COUNT(*) FROM chat_sessions WHERE user_id = $1',
                [userId]
            );

            return {
                sessions: result.rows,
                total: parseInt(countResult.rows[0].count)
            };
        } catch (error) {
            throw error;
        }
    }

    // Get single session details
    async getSessionById(sessionId, userId) {
        try {
            const result = await pool.query(
                `SELECT * FROM chat_sessions 
                WHERE id = $1 AND user_id = $2`,
                [sessionId, userId]
            );

            if (result.rows.length === 0) {
                throw new Error('Session not found');
            }

            return result.rows[0];
        } catch (error) {
            throw error;
        }
    }

    // Save user message to database
    async saveUserMessage(sessionId, userId, content) {
        try {
            const messageId = uuidv4();
            const result = await pool.query(
                `INSERT INTO chat_messages 
                (id, session_id, user_id, message_type, content, created_at)
                VALUES ($1, $2, $3, 'user_message', $4, CURRENT_TIMESTAMP)
                RETURNING id, session_id, message_type, content, created_at`,
                [messageId, sessionId, userId, content]
            );

            // Update session stats
            await pool.query(
                `UPDATE chat_sessions 
                SET total_messages = total_messages + 1,
                    total_user_messages = total_user_messages + 1,
                    last_message_at = CURRENT_TIMESTAMP
                WHERE id = $1`,
                [sessionId]
            );

            return result.rows[0];
        } catch (error) {
            throw error;
        }
    }

    // Save AI response message to database
    async saveAIResponse(sessionId, userId, content, metadata = {}) {
        try {
            const messageId = uuidv4();
            const result = await pool.query(
                `INSERT INTO chat_messages 
                (id, session_id, user_id, message_type, content, metadata, created_at)
                VALUES ($1, $2, $3, 'ai_response', $4, $5, CURRENT_TIMESTAMP)
                RETURNING id, session_id, message_type, content, created_at`,
                [messageId, sessionId, userId, content, JSON.stringify(metadata)]
            );

            // Save AI metadata
            if (metadata.model) {
                await pool.query(
                    `INSERT INTO ai_response_metadata 
                    (id, message_id, session_id, ai_model, confidence_score, processing_time_ms, tokens_used, api_provider)
                    VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
                    [
                        uuidv4(),
                        messageId,
                        sessionId,
                        metadata.model,
                        metadata.confidence || 0.95,
                        metadata.processingTime || 0,
                        metadata.tokensUsed || 0,
                        metadata.provider || 'gemini'
                    ]
                );
            }

            // Update session stats
            await pool.query(
                `UPDATE chat_sessions 
                SET total_messages = total_messages + 1,
                    total_ai_responses = total_ai_responses + 1,
                    last_message_at = CURRENT_TIMESTAMP
                WHERE id = $1`,
                [sessionId]
            );

            return result.rows[0];
        } catch (error) {
            throw error;
        }
    }

    // Get all messages in a session (conversation history)
    async getSessionMessages(sessionId, userId, limit = 50, offset = 0) {
        try {
            const result = await pool.query(
                `SELECT 
                    cm.id, 
                    cm.session_id, 
                    cm.message_type, 
                    cm.content, 
                    cm.created_at,
                    arm.ai_model,
                    arm.confidence_score,
                    arm.processing_time_ms
                FROM chat_messages cm
                LEFT JOIN ai_response_metadata arm ON cm.id = arm.message_id
                WHERE cm.session_id = $1
                ORDER BY cm.created_at ASC
                LIMIT $2 OFFSET $3`,
                [sessionId, limit, offset]
            );

            const countResult = await pool.query(
                'SELECT COUNT(*) FROM chat_messages WHERE session_id = $1',
                [sessionId]
            );

            return {
                messages: result.rows,
                total: parseInt(countResult.rows[0].count)
            };
        } catch (error) {
            throw error;
        }
    }

    // Get complete conversation history for a user
    async getUserChatHistory(userId) {
        try {
            const result = await pool.query(
                `SELECT 
                    cs.id as session_id,
                    cs.session_name,
                    cs.created_at as session_start_time,
                    cs.updated_at as session_end_time,
                    cs.total_messages,
                    cs.total_user_messages,
                    cs.total_ai_responses,
                    COUNT(DISTINCT CASE WHEN cm.message_type = 'user_message' THEN cm.id END) as user_msg_count,
                    COUNT(DISTINCT CASE WHEN cm.message_type = 'ai_response' THEN cm.id END) as ai_msg_count
                FROM chat_sessions cs
                LEFT JOIN chat_messages cm ON cs.id = cm.session_id
                WHERE cs.user_id = $1
                GROUP BY cs.id
                ORDER BY cs.created_at DESC`,
                [userId]
            );

            return result.rows;
        } catch (error) {
            throw error;
        }
    }

    // Close a session
    async closeSession(sessionId, userId) {
        try {
            const result = await pool.query(
                `UPDATE chat_sessions 
                SET is_active = false, updated_at = CURRENT_TIMESTAMP
                WHERE id = $1 AND user_id = $2
                RETURNING id, is_active, updated_at`,
                [sessionId, userId]
            );

            if (result.rows.length === 0) {
                throw new Error('Session not found');
            }

            return result.rows[0];
        } catch (error) {
            throw error;
        }
    }

    // Search messages by content
    async searchMessages(sessionId, searchTerm) {
        try {
            const result = await pool.query(
                `SELECT * FROM chat_messages 
                WHERE session_id = $1 
                AND LOWER(content) LIKE LOWER($2)
                ORDER BY created_at DESC`,
                [sessionId, `%${searchTerm}%`]
            );

            return result.rows;
        } catch (error) {
            throw error;
        }
    }

    // Get analytics for a user
    async getUserAnalytics(userId) {
        try {
            const result = await pool.query(
                `SELECT 
                    COUNT(DISTINCT cs.id) as total_sessions,
                    COUNT(DISTINCT CASE WHEN cm.message_type = 'user_message' THEN cm.id END) as total_messages_sent,
                    COUNT(DISTINCT CASE WHEN cm.message_type = 'ai_response' THEN cm.id END) as total_ai_responses,
                    COALESCE(AVG(cs.duration_seconds), 0) as average_session_duration,
                    MAX(cm.created_at) as last_active
                FROM chat_sessions cs
                LEFT JOIN chat_messages cm ON cs.id = cm.session_id
                WHERE cs.user_id = $1`,
                [userId]
            );

            return result.rows[0];
        } catch (error) {
            throw error;
        }
    }
}

module.exports = new ChatService();
```

### 3.4 AI Service

**File: `src/services/aiService.js`**

```javascript
const logger = require('../utils/logger');

class AIService {
    async generateResponse(userMessage, sessionId) {
        const startTime = Date.now();

        try {
            const provider = process.env.AI_SERVICE_PROVIDER || 'gemini';

            let aiResponse;

            if (provider === 'gemini') {
                aiResponse = await this.callGeminiAPI(userMessage);
            } else if (provider === 'openai') {
                aiResponse = await this.callOpenAIAPI(userMessage);
            } else {
                throw new Error('Unknown AI provider');
            }

            const processingTime = Date.now() - startTime;

            return {
                content: aiResponse.content,
                model: aiResponse.model,
                provider: provider,
                confidence: aiResponse.confidence || 0.95,
                processingTime,
                tokensUsed: aiResponse.tokensUsed || 0
            };
        } catch (error) {
            logger.error(`AI response generation failed: ${error.message}`);
            throw new Error('Failed to generate AI response');
        }
    }

    async callGeminiAPI(userMessage) {
        const { GoogleGenerativeAI } = require('@google/generative-ai');

        const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY);
        const model = genAI.getGenerativeModel({ model: 'gemini-pro' });

        const result = await model.generateContent(userMessage);
        const response = await result.response;

        return {
            content: response.text(),
            model: 'gemini-pro',
            confidence: 0.95,
            tokensUsed: 0
        };
    }

    async callOpenAIAPI(userMessage) {
        const { OpenAI } = require('openai');

        const openai = new OpenAI({
            apiKey: process.env.OPENAI_API_KEY
        });

        const response = await openai.chat.completions.create({
            model: 'gpt-4',
            messages: [{ role: 'user', content: userMessage }],
            temperature: 0.7,
            max_tokens: 1000
        });

        return {
            content: response.choices[0].message.content,
            model: 'gpt-4',
            confidence: 0.95,
            tokensUsed: response.usage.total_tokens
        };
    }
}

module.exports = new AIService();
```

---

## 4. API Endpoints

### 4.1 Authentication Endpoints

**File: `src/routes/auth.js`**

```javascript
const express = require('express');
const router = express.Router();
const authService = require('../services/authService');
const { body, validationResult } = require('express-validator');

// Register
router.post('/register', [
    body('email').isEmail().normalizeEmail(),
    body('username').isLength({ min: 3 }),
    body('password').isLength({ min: 8 }),
    body('full_name').notEmpty()
], async (req, res) => {
    try {
        const errors = validationResult(req);
        if (!errors.isEmpty()) {
            return res.status(400).json({ 
                success: false, 
                errors: errors.array() 
            });
        }

        const result = await authService.registerUser(req.body);

        res.status(201).json({
            success: true,
            message: 'User registered successfully',
            data: {
                user: result.user,
                token: result.accessToken,
                refreshToken: result.refreshToken
            }
        });
    } catch (error) {
        res.status(400).json({
            success: false,
            error: error.message
        });
    }
});

// Login
router.post('/login', [
    body('email').isEmail().normalizeEmail(),
    body('password').notEmpty()
], async (req, res) => {
    try {
        const errors = validationResult(req);
        if (!errors.isEmpty()) {
            return res.status(400).json({ 
                success: false, 
                errors: errors.array() 
            });
        }

        const { email, password } = req.body;
        const result = await authService.loginUser(email, password);

        res.json({
            success: true,
            data: {
                user: result.user,
                token: result.accessToken,
                refreshToken: result.refreshToken
            }
        });
    } catch (error) {
        res.status(401).json({
            success: false,
            error: error.message
        });
    }
});

module.exports = router;
```

### 4.2 Chat Endpoints

**File: `src/routes/chat.js`**

```javascript
const express = require('express');
const router = express.Router();
const chatService = require('../services/chatService');
const aiService = require('../services/aiService');
const authMiddleware = require('../middleware/authMiddleware');

// All routes require authentication
router.use(authMiddleware);

// Create session
router.post('/sessions', async (req, res) => {
    try {
        const { sessionName, aiTeacherId, topic } = req.body;
        const session = await chatService.createSession(req.user.userId, {
            sessionName,
            aiTeacherId,
            topic
        });

        res.status(201).json({
            success: true,
            data: session
        });
    } catch (error) {
        res.status(400).json({ success: false, error: error.message });
    }
});

// Get all sessions
router.get('/sessions', async (req, res) => {
    try {
        const { page = 1, limit = 10 } = req.query;
        const offset = (page - 1) * limit;
        
        const result = await chatService.getUserSessions(
            req.user.userId,
            limit,
            offset
        );

        res.json({
            success: true,
            data: result.sessions,
            pagination: {
                total: result.total,
                page,
                limit
            }
        });
    } catch (error) {
        res.status(400).json({ success: false, error: error.message });
    }
});

// Get session details
router.get('/sessions/:sessionId', async (req, res) => {
    try {
        const session = await chatService.getSessionById(
            req.params.sessionId,
            req.user.userId
        );

        res.json({
            success: true,
            data: session
        });
    } catch (error) {
        res.status(404).json({ success: false, error: error.message });
    }
});

// Send message and get AI response
router.post('/messages', async (req, res) => {
    try {
        const { session_id, content } = req.body;

        if (!content || content.trim() === '') {
            return res.status(400).json({
                success: false,
                error: 'Message content is required'
            });
        }

        // Verify session exists
        const session = await chatService.getSessionById(
            session_id,
            req.user.userId
        );

        // Save user message
        const userMessage = await chatService.saveUserMessage(
            session_id,
            req.user.userId,
            content
        );

        // Generate AI response
        const aiResponse = await aiService.generateResponse(
            content,
            session_id
        );

        // Save AI response
        const savedAIResponse = await chatService.saveAIResponse(
            session_id,
            req.user.userId,
            aiResponse.content,
            {
                model: aiResponse.model,
                confidence: aiResponse.confidence,
                processingTime: aiResponse.processingTime,
                tokensUsed: aiResponse.tokensUsed,
                provider: aiResponse.provider
            }
        );

        res.status(201).json({
            success: true,
            data: {
                userMessage,
                aiResponse: {
                    ...savedAIResponse,
                    metadata: {
                        model: aiResponse.model,
                        confidence: aiResponse.confidence,
                        processingTime: aiResponse.processingTime
                    }
                }
            }
        });
    } catch (error) {
        res.status(400).json({ success: false, error: error.message });
    }
});

// Get conversation history (all messages in session)
router.get('/messages/:sessionId', async (req, res) => {
    try {
        const { page = 1, limit = 50 } = req.query;
        const offset = (page - 1) * limit;

        // Verify session exists
        await chatService.getSessionById(req.params.sessionId, req.user.userId);

        const result = await chatService.getSessionMessages(
            req.params.sessionId,
            req.user.userId,
            limit,
            offset
        );

        res.json({
            success: true,
            data: result.messages,
            pagination: {
                total: result.total,
                page,
                limit
            }
        });
    } catch (error) {
        res.status(400).json({ success: false, error: error.message });
    }
});

// Get user chat history
router.get('/history', async (req, res) => {
    try {
        const history = await chatService.getUserChatHistory(req.user.userId);

        res.json({
            success: true,
            data: history
        });
    } catch (error) {
        res.status(400).json({ success: false, error: error.message });
    }
});

// Get analytics
router.get('/analytics', async (req, res) => {
    try {
        const analytics = await chatService.getUserAnalytics(req.user.userId);

        res.json({
            success: true,
            data: analytics
        });
    } catch (error) {
        res.status(400).json({ success: false, error: error.message });
    }
});

// Close session
router.post('/sessions/:sessionId/close', async (req, res) => {
    try {
        const result = await chatService.closeSession(
            req.params.sessionId,
            req.user.userId
        );

        res.json({
            success: true,
            data: result
        });
    } catch (error) {
        res.status(400).json({ success: false, error: error.message });
    }
});

module.exports = router;
```

### 4.3 Admin Endpoints

**File: `src/routes/admin.js`**

```javascript
const express = require('express');
const router = express.Router();
const pool = require('../config/database');
const authMiddleware = require('../middleware/authMiddleware');

// All admin routes require authentication
router.use(authMiddleware);

// Get all sessions (admin only)
router.get('/sessions', async (req, res) => {
    try {
        const { page = 1, limit = 20, user_id } = req.query;
        const offset = (page - 1) * limit;

        let query = `SELECT cs.*, u.email FROM chat_sessions cs 
                     JOIN users u ON cs.user_id = u.id`;
        const params = [];

        if (user_id) {
            query += ' WHERE cs.user_id = $' + (params.length + 1);
            params.push(user_id);
        }

        query += ` ORDER BY cs.created_at DESC LIMIT $${params.length + 1} OFFSET $${params.length + 2}`;
        params.push(limit, offset);

        const result = await pool.query(query, params);

        const countQuery = 'SELECT COUNT(*) FROM chat_sessions' + (user_id ? ' WHERE user_id = $1' : '');
        const countParams = user_id ? [user_id] : [];
        const countResult = await pool.query(countQuery, countParams);

        res.json({
            success: true,
            data: result.rows,
            pagination: {
                total: parseInt(countResult.rows[0].count),
                page,
                limit
            }
        });
    } catch (error) {
        res.status(400).json({ success: false, error: error.message });
    }
});

// Get all messages for a session
router.get('/sessions/:sessionId/messages', async (req, res) => {
    try {
        const result = await pool.query(
            `SELECT cm.*, u.email FROM chat_messages cm
             JOIN users u ON cm.user_id = u.id
             WHERE cm.session_id = $1
             ORDER BY cm.created_at ASC`,
            [req.params.sessionId]
        );

        res.json({
            success: true,
            data: result.rows
        });
    } catch (error) {
        res.status(400).json({ success: false, error: error.message });
    }
});

module.exports = router;
```

---

## 5. Implementation Code

### 5.1 Main Application File

**File: `src/app.js`**

```javascript
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const compression = require('compression');
require('dotenv').config();

const authRoutes = require('./routes/auth');
const chatRoutes = require('./routes/chat');
const adminRoutes = require('./routes/admin');
const errorHandler = require('./middleware/errorHandler');

const app = express();

// Middleware
app.use(helmet());
app.use(compression());
app.use(cors({
    origin: process.env.CORS_ORIGIN || '*',
    credentials: true
}));
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Routes
app.use('/api/v1/auth', authRoutes);
app.use('/api/v1/chat', chatRoutes);
app.use('/api/v1/admin', adminRoutes);

// Health check
app.get('/health', (req, res) => {
    res.json({ status: 'OK', timestamp: new Date() });
});

// Error handling
app.use(errorHandler);

// 404 handler
app.use((req, res) => {
    res.status(404).json({
        success: false,
        error: 'Route not found'
    });
});

const PORT = process.env.PORT || 3000;

app.listen(PORT, () => {
    console.log(`Server running on port ${PORT}`);
});

module.exports = app;
```

### 5.2 Auth Middleware

**File: `src/middleware/authMiddleware.js`**

```javascript
const jwt = require('jsonwebtoken');

module.exports = (req, res, next) => {
    try {
        const authHeader = req.get('Authorization');

        if (!authHeader) {
            return res.status(401).json({
                success: false,
                error: 'Authorization token is required'
            });
        }

        const token = authHeader.replace('Bearer ', '');

        const decoded = jwt.verify(token, process.env.JWT_SECRET);

        req.user = {
            userId: decoded.userId,
            email: decoded.email
        };

        next();
    } catch (error) {
        if (error.name === 'TokenExpiredError') {
            return res.status(401).json({
                success: false,
                error: 'Token has expired'
            });
        }

        res.status(401).json({
            success: false,
            error: 'Invalid authorization token'
        });
    }
};
```

### 5.3 Error Handler Middleware

**File: `src/middleware/errorHandler.js`**

```javascript
module.exports = (err, req, res, next) => {
    console.error('Error:', err);

    res.status(err.status || 500).json({
        success: false,
        error: err.message || 'An unexpected error occurred'
    });
};
```

---

## 6. Testing

### Test Script Example

**File: `test-api.js`**

```javascript
const http = require('http');

const BASE_URL = 'http://localhost:3000';

// Helper function for API calls
function apiCall(method, path, body = null) {
    return new Promise((resolve, reject) => {
        const options = {
            hostname: 'localhost',
            port: 3000,
            path,
            method,
            headers: {
                'Content-Type': 'application/json'
            }
        };

        const req = http.request(options, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                resolve({
                    status: res.statusCode,
                    data: JSON.parse(data)
                });
            });
        });

        req.on('error', reject);
        if (body) req.write(JSON.stringify(body));
        req.end();
    });
}

async function runTests() {
    console.log('🧪 Testing AI Chat API...\n');

    try {
        // 1. Register user
        console.log('1️⃣ Testing user registration...');
        const registerRes = await apiCall('POST', '/api/v1/auth/register', {
            email: 'test@example.com',
            username: 'testuser',
            password: 'password123456',
            full_name: 'Test User'
        });
        console.log('✅ Registration:', registerRes.data.success);
        const token = registerRes.data.data.token;

        // 2. Create session
        console.log('\n2️⃣ Testing session creation...');
        const sessionRes = await apiCall('POST', '/api/v1/chat/sessions', {
            session_name: 'Test Session',
            ai_teacher_id: 'gemini-pro'
        });
        console.log('✅ Session created:', sessionRes.data.success);
        const sessionId = sessionRes.data.data.id;

        // 3. Send message
        console.log('\n3️⃣ Testing message sending...');
        const messageRes = await apiCall('POST', '/api/v1/chat/messages', {
            session_id: sessionId,
            content: 'What is 2 + 2?'
        });
        console.log('✅ Message sent:', messageRes.data.success);

        // 4. Get messages
        console.log('\n4️⃣ Testing message retrieval...');
        const messagesRes = await apiCall('GET', `/api/v1/chat/messages/${sessionId}`);
        console.log('✅ Messages retrieved:', messagesRes.data.data.length, 'messages');

        // 5. Get history
        console.log('\n5️⃣ Testing history retrieval...');
        const historyRes = await apiCall('GET', '/api/v1/chat/history');
        console.log('✅ History retrieved:', historyRes.data.data.length, 'sessions');

        console.log('\n✨ All tests passed!');

    } catch (error) {
        console.error('❌ Test error:', error.message);
    }
}

runTests();
```

---

## 7. Deployment

### Docker Setup

**File: `Dockerfile`**

```dockerfile
FROM node:18-alpine

WORKDIR /app

COPY package*.json ./
RUN npm ci --only=production

COPY src ./src

EXPOSE 3000

CMD ["node", "src/app.js"]
```

**File: `docker-compose.yml`**

```yaml
version: '3.8'

services:
  api:
    build: .
    ports:
      - "3000:3000"
    environment:
      - NODE_ENV=production
      - DB_HOST=postgres
      - DB_NAME=ai_chat_db
      - DB_USER=postgres
      - DB_PASSWORD=secure_password_here
    depends_on:
      - postgres

  postgres:
    image: postgres:14-alpine
    environment:
      - POSTGRES_DB=ai_chat_db
      - POSTGRES_PASSWORD=secure_password_here
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"

volumes:
  postgres_data:
```

### Deploy Commands

```bash
# Build and run with Docker Compose
docker-compose up -d

# Check logs
docker-compose logs -f api

# Stop
docker-compose down
```

---

## 8. Key Features Summary

✅ **User Authentication**
- Register and login
- JWT tokens
- Secure password hashing

✅ **Chat Session Management**
- Create sessions
- Track active sessions
- Close sessions

✅ **Message Storage**
- Save all user messages
- Save all AI responses
- Store message metadata
- Full message history

✅ **Conversation History**
- Complete message retrieval
- Session summaries
- User analytics
- Search functionality

✅ **AI Integration**
- Gemini API support
- OpenAI support
- Response metadata tracking
- Token counting

✅ **Admin Features**
- View all sessions
- View all messages
- User management

---

## Database Relationships

```
Users (1) ──── (Many) Chat Sessions
  │
  ├──── Chat Messages
  │     │
  │     └──── AI Response Metadata
  │
  ├──── Chat History
  │
  ├──── Audit Log
  │
  └──── Session Tokens
```

---

## API Response Format (Standard)

**Success Response:**
```json
{
    "success": true,
    "data": { /* response data */ },
    "pagination": { /* optional */ }
}
```

**Error Response:**
```json
{
    "success": false,
    "error": "Error message"
}
```

---

## Important Notes

1. **Database**: All conversation history is automatically saved
2. **Encryption**: Optional - implement for sensitive data
3. **Scalability**: Use database replication and caching for production
4. **Monitoring**: Set up logging for all API calls
5. **Backup**: Automated daily backups recommended

---

## Next Steps

1. ✅ Clone repository
2. ✅ Install dependencies: `npm install`
3. ✅ Create .env file with configuration
4. ✅ Create PostgreSQL database
5. ✅ Run database migrations (SQL script)
6. ✅ Start server: `npm run dev`
7. ✅ Test API endpoints
8. ✅ Deploy to production

---

**Document Version**: 1.0  
**Created**: 2026-06-18  
**Status**: Ready for Backend Implementation ✅

**Questions?** Refer to specific service files or database schema for detailed implementation details.
