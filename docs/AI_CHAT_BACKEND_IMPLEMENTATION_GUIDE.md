# AI Chat Feature - Backend Implementation Guide

**Version**: 1.0  
**Created**: 2026-06-18  
**Target Stack**: Node.js with Express.js & PostgreSQL

---

## Table of Contents

1. [Project Setup](#1-project-setup)
2. [Core Service Implementation](#2-core-service-implementation)
3. [API Controller Implementation](#3-api-controller-implementation)
4. [Middleware Implementation](#4-middleware-implementation)
5. [Error Handling](#5-error-handling)
6. [Testing Strategy](#6-testing-strategy)
7. [Deployment Guide](#7-deployment-guide)

---

## 1. Project Setup

### 1.1 Initialize Project

```bash
# Create project directory
mkdir ai-chat-backend
cd ai-chat-backend

# Initialize Node.js project
npm init -y

# Install core dependencies
npm install express pg dotenv bcryptjs jsonwebtoken joi cors helmet express-validator

# Install development dependencies
npm install --save-dev nodemon jest supertest eslint prettier

# Install additional utilities
npm install winston uuid multer helmet cors compression
```

### 1.2 Project Structure

```
ai-chat-backend/
├── src/
│   ├── config/
│   │   ├── database.js
│   │   ├── environment.js
│   │   └── constants.js
│   ├── controllers/
│   │   ├── authController.js
│   │   ├── chatSessionController.js
│   │   ├── messageController.js
│   │   ├── historyController.js
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
│   │   ├── validator.js
│   │   ├── auditLogger.js
│   │   └── rateLimiter.js
│   ├── models/
│   │   ├── User.js
│   │   ├── ChatSession.js
│   │   └── Message.js
│   ├── routes/
│   │   ├── auth.js
│   │   ├── chat.js
│   │   ├── admin.js
│   │   └── index.js
│   ├── utils/
│   │   ├── validators.js
│   │   ├── encryption.js
│   │   ├── helpers.js
│   │   └── logger.js
│   ├── db/
│   │   ├── migrations/
│   │   └── seeds/
│   └── app.js
├── tests/
│   ├── unit/
│   ├── integration/
│   └── fixtures/
├── .env.example
├── .env.local (local only)
├── package.json
├── jest.config.js
├── .eslintrc.json
└── README.md
```

### 1.3 Environment Variables (.env.example)

```env
# Server
NODE_ENV=development
PORT=3000
HOST=localhost

# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=ai_chat_db
DB_USER=postgres
DB_PASSWORD=your_secure_password
DB_SSL=false
DB_POOL_MIN=2
DB_POOL_MAX=10

# JWT
JWT_SECRET=your_super_secret_jwt_key_change_in_production
JWT_EXPIRY=24h
JWT_REFRESH_SECRET=your_refresh_token_secret
JWT_REFRESH_EXPIRY=7d

# Encryption
ENCRYPTION_KEY=your_32_char_encryption_key_here!
ENCRYPTION_IV=your_16_char_iv_here!

# External AI Service
AI_SERVICE_PROVIDER=gemini # gemini or openai
GEMINI_API_KEY=your_gemini_api_key
OPENAI_API_KEY=your_openai_api_key

# Logging
LOG_LEVEL=info
LOG_FORMAT=json

# Rate Limiting
RATE_LIMIT_WINDOW_MS=900000 # 15 minutes
RATE_LIMIT_MAX_REQUESTS=100

# CORS
CORS_ORIGIN=http://localhost:3001,https://yourdomain.com

# AWS S3 (if storing files)
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=your_access_key
AWS_SECRET_ACCESS_KEY=your_secret_key
AWS_S3_BUCKET=your_bucket_name
```

### 1.4 Package.json Scripts

```json
{
  "scripts": {
    "start": "node src/app.js",
    "dev": "nodemon src/app.js",
    "test": "jest --forceExit --detectOpenHandles",
    "test:watch": "jest --watch",
    "test:coverage": "jest --coverage",
    "lint": "eslint src/**/*.js",
    "lint:fix": "eslint src/**/*.js --fix",
    "db:migrate": "node src/db/migrate.js",
    "db:seed": "node src/db/seed.js",
    "db:rollback": "node src/db/rollback.js"
  }
}
```

---

## 2. Core Service Implementation

### 2.1 Database Configuration

```javascript
// src/config/database.js
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
    connectionTimeoutMillis: 2000,
    ssl: process.env.DB_SSL === 'true' ? { rejectUnauthorized: false } : false
});

pool.on('error', (err) => {
    console.error('Unexpected error on idle client', err);
});

pool.on('connect', () => {
    console.log('Database connected successfully');
});

module.exports = pool;
```

### 2.2 Auth Service

```javascript
// src/services/authService.js
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const { v4: uuidv4 } = require('uuid');
const pool = require('../config/database');
const logger = require('../utils/logger');

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

            // Insert user
            const result = await pool.query(
                `INSERT INTO users 
                (id, email, username, password_hash, full_name, phone_number, user_role, created_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, CURRENT_TIMESTAMP)
                RETURNING id, email, username, full_name, user_role, created_at`,
                [uuidv4(), email, username, hashedPassword, fullName, phoneNumber, 'student']
            );

            const user = result.rows[0];

            // Generate tokens
            const { accessToken, refreshToken } = this.generateTokens(user.id, email);

            // Store refresh token
            await this.storeRefreshToken(user.id, refreshToken);

            logger.info(`User registered successfully: ${email}`);

            return {
                user,
                accessToken,
                refreshToken
            };
        } catch (error) {
            logger.error(`User registration failed: ${error.message}`);
            throw error;
        }
    }

    async loginUser(email, password) {
        try {
            // Get user
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

            // Update last_login
            await pool.query(
                'UPDATE users SET last_login = CURRENT_TIMESTAMP WHERE id = $1',
                [user.id]
            );

            // Generate tokens
            const { accessToken, refreshToken } = this.generateTokens(user.id, email);

            // Store refresh token
            await this.storeRefreshToken(user.id, refreshToken);

            logger.info(`User logged in successfully: ${email}`);

            return {
                user: {
                    id: user.id,
                    email: user.email,
                    username: user.username,
                    full_name: user.full_name,
                    user_role: user.user_role
                },
                accessToken,
                refreshToken
            };
        } catch (error) {
            logger.error(`User login failed: ${error.message}`);
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
        expiresAt.setDate(expiresAt.getDate() + 7); // 7 days

        await pool.query(
            `INSERT INTO user_session_tokens 
            (id, user_id, refresh_token, expires_at, created_at)
            VALUES ($1, $2, $3, $4, CURRENT_TIMESTAMP)`,
            [uuidv4(), userId, refreshToken, expiresAt]
        );
    }

    async verifyRefreshToken(userId, refreshToken) {
        try {
            const result = await pool.query(
                `SELECT * FROM user_session_tokens 
                WHERE user_id = $1 AND refresh_token = $2 AND expires_at > CURRENT_TIMESTAMP
                AND revoked_at IS NULL`,
                [userId, refreshToken]
            );

            return result.rows.length > 0;
        } catch (error) {
            logger.error(`Refresh token verification failed: ${error.message}`);
            throw error;
        }
    }

    async refreshToken(userId, refreshToken) {
        const isValid = await this.verifyRefreshToken(userId, refreshToken);

        if (!isValid) {
            throw new Error('Invalid or expired refresh token');
        }

        const user = await pool.query('SELECT email FROM users WHERE id = $1', [userId]);
        const { accessToken, refreshToken: newRefreshToken } = this.generateTokens(
            userId,
            user.rows[0].email
        );

        await this.storeRefreshToken(userId, newRefreshToken);

        return { accessToken, refreshToken: newRefreshToken };
    }
}

module.exports = new AuthService();
```

### 2.3 Chat Service

```javascript
// src/services/chatService.js
const { v4: uuidv4 } = require('uuid');
const pool = require('../config/database');
const aiService = require('./aiService');
const encryptionService = require('./encryptionService');
const auditService = require('./auditService');
const logger = require('../utils/logger');

class ChatService {
    async createSession(userId, sessionData) {
        const { sessionName, aiTeacherId, topic } = sessionData;

        try {
            const result = await pool.query(
                `INSERT INTO chat_sessions 
                (id, user_id, session_name, ai_teacher_id, ai_model, topic, created_at, updated_at)
                VALUES ($1, $2, $3, $4, $5, $6, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id, user_id, session_name, created_at, is_active`,
                [uuidv4(), userId, sessionName, aiTeacherId, aiTeacherId, topic]
            );

            const session = result.rows[0];

            // Log audit
            await auditService.logAction(userId, null, 'session_created', {
                session_id: session.id,
                session_name: sessionName
            });

            logger.info(`Chat session created: ${session.id}`);

            return session;
        } catch (error) {
            logger.error(`Failed to create chat session: ${error.message}`);
            throw error;
        }
    }

    async getSessionById(sessionId, userId) {
        try {
            const result = await pool.query(
                `SELECT * FROM chat_sessions 
                WHERE id = $1 AND user_id = $2`,
                [sessionId, userId]
            );

            if (result.rows.length === 0) {
                throw new Error('Chat session not found');
            }

            return result.rows[0];
        } catch (error) {
            logger.error(`Failed to get chat session: ${error.message}`);
            throw error;
        }
    }

    async getAllSessions(userId, pagination = {}) {
        const { page = 1, limit = 10 } = pagination;
        const offset = (page - 1) * limit;

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
                total: parseInt(countResult.rows[0].count),
                page,
                limit
            };
        } catch (error) {
            logger.error(`Failed to get chat sessions: ${error.message}`);
            throw error;
        }
    }

    async closeSession(sessionId, userId) {
        try {
            const result = await pool.query(
                `UPDATE chat_sessions 
                SET is_active = false, status = 'closed', closed_at = CURRENT_TIMESTAMP
                WHERE id = $1 AND user_id = $2
                RETURNING id, is_active, closed_at`,
                [sessionId, userId]
            );

            if (result.rows.length === 0) {
                throw new Error('Chat session not found');
            }

            // Log audit
            await auditService.logAction(userId, sessionId, 'session_closed', {});

            logger.info(`Chat session closed: ${sessionId}`);

            return result.rows[0];
        } catch (error) {
            logger.error(`Failed to close chat session: ${error.message}`);
            throw error;
        }
    }

    async sendMessage(sessionId, userId, content) {
        const client = await pool.connect();

        try {
            await client.query('BEGIN');

            // Verify session exists and belongs to user
            const sessionResult = await client.query(
                'SELECT * FROM chat_sessions WHERE id = $1 AND user_id = $2',
                [sessionId, userId]
            );

            if (sessionResult.rows.length === 0) {
                throw new Error('Chat session not found');
            }

            // Save user message
            const messageId = uuidv4();
            const encryptedContent = encryptionService.encrypt(content);

            await client.query(
                `INSERT INTO chat_messages 
                (id, session_id, user_id, message_type, content, encrypted_content, is_encrypted, created_at)
                VALUES ($1, $2, $3, 'user_message', $4, $5, true, CURRENT_TIMESTAMP)`,
                [messageId, sessionId, userId, content, encryptedContent]
            );

            // Get AI response
            const aiResponse = await aiService.generateResponse(content, sessionId, userId);

            // Save AI response
            const responseMessageId = uuidv4();
            const encryptedResponse = encryptionService.encrypt(aiResponse.content);

            await client.query(
                `INSERT INTO chat_messages 
                (id, session_id, user_id, message_type, content, encrypted_content, is_encrypted, created_at)
                VALUES ($1, $2, $3, 'ai_response', $4, $5, true, CURRENT_TIMESTAMP)`,
                [responseMessageId, sessionId, userId, aiResponse.content, encryptedResponse]
            );

            // Save AI metadata
            await client.query(
                `INSERT INTO ai_response_metadata 
                (id, message_id, session_id, ai_model, confidence_score, processing_time_ms, tokens_used, api_provider)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
                [
                    uuidv4(),
                    responseMessageId,
                    sessionId,
                    aiResponse.model,
                    aiResponse.confidence,
                    aiResponse.processingTime,
                    aiResponse.tokensUsed,
                    aiResponse.provider
                ]
            );

            // Update session stats
            await client.query(
                `UPDATE chat_sessions 
                SET total_messages = total_messages + 2,
                    total_user_messages = total_user_messages + 1,
                    total_ai_responses = total_ai_responses + 1,
                    last_message_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = $1`,
                [sessionId]
            );

            // Log audit
            await auditService.logAction(userId, sessionId, 'message_sent', {
                message_id: messageId
            });

            await client.query('COMMIT');

            logger.info(`Message sent in session: ${sessionId}`);

            return {
                userMessage: {
                    id: messageId,
                    type: 'user_message',
                    content
                },
                aiResponse: {
                    id: responseMessageId,
                    type: 'ai_response',
                    content: aiResponse.content,
                    metadata: {
                        model: aiResponse.model,
                        confidence: aiResponse.confidence,
                        processingTime: aiResponse.processingTime
                    }
                }
            };
        } catch (error) {
            await client.query('ROLLBACK');
            logger.error(`Failed to send message: ${error.message}`);
            throw error;
        } finally {
            client.release();
        }
    }

    async getMessages(sessionId, userId, pagination = {}) {
        const { page = 1, limit = 20 } = pagination;
        const offset = (page - 1) * limit;

        try {
            const result = await pool.query(
                `SELECT cm.id, cm.session_id, cm.message_type, cm.content, cm.created_at
                FROM chat_messages cm
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
                total: parseInt(countResult.rows[0].count),
                page,
                limit
            };
        } catch (error) {
            logger.error(`Failed to get messages: ${error.message}`);
            throw error;
        }
    }
}

module.exports = new ChatService();
```

### 2.4 AI Service

```javascript
// src/services/aiService.js
const logger = require('../utils/logger');

class AIService {
    async generateResponse(userMessage, sessionId, userId) {
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
        const text = response.text();

        return {
            content: text,
            model: 'gemini-pro',
            confidence: 0.95,
            tokensUsed: result.response.usageMetadata?.totalTokenCount || 0
        };
    }

    async callOpenAIAPI(userMessage) {
        const { OpenAI } = require('openai');

        const openai = new OpenAI({
            apiKey: process.env.OPENAI_API_KEY
        });

        const response = await openai.chat.completions.create({
            model: 'gpt-4',
            messages: [
                {
                    role: 'user',
                    content: userMessage
                }
            ],
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

### 2.5 Encryption Service

```javascript
// src/services/encryptionService.js
const crypto = require('crypto');
const logger = require('../utils/logger');

class EncryptionService {
    constructor() {
        this.algorithm = 'aes-256-gcm';
        this.encryptionKey = Buffer.from(process.env.ENCRYPTION_KEY, 'hex');
    }

    encrypt(plainText) {
        try {
            const iv = crypto.randomBytes(16);
            const cipher = crypto.createCipheriv(this.algorithm, this.encryptionKey, iv);

            let encryptedData = cipher.update(plainText, 'utf8', 'hex');
            encryptedData += cipher.final('hex');

            const authTag = cipher.getAuthTag();

            return `${iv.toString('hex')}:${encryptedData}:${authTag.toString('hex')}`;
        } catch (error) {
            logger.error(`Encryption failed: ${error.message}`);
            throw error;
        }
    }

    decrypt(encryptedText) {
        try {
            const parts = encryptedText.split(':');
            const iv = Buffer.from(parts[0], 'hex');
            const encryptedData = parts[1];
            const authTag = Buffer.from(parts[2], 'hex');

            const decipher = crypto.createDecipheriv(this.algorithm, this.encryptionKey, iv);
            decipher.setAuthTag(authTag);

            let decryptedData = decipher.update(encryptedData, 'hex', 'utf8');
            decryptedData += decipher.final('utf8');

            return decryptedData;
        } catch (error) {
            logger.error(`Decryption failed: ${error.message}`);
            throw error;
        }
    }
}

module.exports = new EncryptionService();
```

### 2.6 Audit Service

```javascript
// src/services/auditService.js
const { v4: uuidv4 } = require('uuid');
const pool = require('../config/database');
const logger = require('../utils/logger');

class AuditService {
    async logAction(userId, sessionId, action, details, req = null) {
        try {
            const ipAddress = req?.ip || '0.0.0.0';
            const userAgent = req?.get('user-agent') || '';

            await pool.query(
                `INSERT INTO chat_audit_log 
                (id, user_id, session_id, action, action_details, ip_address, user_agent, status, created_at)
                VALUES ($1, $2, $3, $4, $5, $6, $7, 'success', CURRENT_TIMESTAMP)`,
                [uuidv4(), userId, sessionId, action, JSON.stringify(details), ipAddress, userAgent]
            );

            logger.info(`Audit log created: ${action} by user ${userId}`);
        } catch (error) {
            logger.error(`Failed to create audit log: ${error.message}`);
            // Don't throw - audit logging should not break the main operation
        }
    }

    async getAuditLog(userId, filters = {}) {
        try {
            let query = 'SELECT * FROM chat_audit_log WHERE user_id = $1';
            const params = [userId];
            let paramIndex = 2;

            if (filters.action) {
                query += ` AND action = $${paramIndex}`;
                params.push(filters.action);
                paramIndex++;
            }

            if (filters.dateFrom) {
                query += ` AND created_at >= $${paramIndex}`;
                params.push(filters.dateFrom);
                paramIndex++;
            }

            if (filters.dateTo) {
                query += ` AND created_at <= $${paramIndex}`;
                params.push(filters.dateTo);
                paramIndex++;
            }

            query += ' ORDER BY created_at DESC LIMIT 1000';

            const result = await pool.query(query, params);
            return result.rows;
        } catch (error) {
            logger.error(`Failed to get audit log: ${error.message}`);
            throw error;
        }
    }
}

module.exports = new AuditService();
```

---

## 3. API Controller Implementation

### 3.1 Auth Controller

```javascript
// src/controllers/authController.js
const authService = require('../services/authService');
const logger = require('../utils/logger');
const { validationResult } = require('express-validator');

exports.register = async (req, res) => {
    try {
        const errors = validationResult(req);
        if (!errors.isEmpty()) {
            return res.status(400).json({
                success: false,
                error: {
                    code: 'VALIDATION_ERROR',
                    message: 'Invalid request parameters',
                    details: errors.array()
                }
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
        logger.error(`Registration error: ${error.message}`);
        res.status(400).json({
            success: false,
            error: {
                code: 'REGISTRATION_FAILED',
                message: error.message
            }
        });
    }
};

exports.login = async (req, res) => {
    try {
        const errors = validationResult(req);
        if (!errors.isEmpty()) {
            return res.status(400).json({
                success: false,
                error: {
                    code: 'VALIDATION_ERROR',
                    details: errors.array()
                }
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
        logger.error(`Login error: ${error.message}`);
        res.status(401).json({
            success: false,
            error: {
                code: 'LOGIN_FAILED',
                message: error.message
            }
        });
    }
};

exports.refreshToken = async (req, res) => {
    try {
        const { refreshToken } = req.body;
        const userId = req.user.userId;

        const result = await authService.refreshToken(userId, refreshToken);

        res.json({
            success: true,
            data: {
                token: result.accessToken,
                refreshToken: result.refreshToken
            }
        });
    } catch (error) {
        logger.error(`Token refresh error: ${error.message}`);
        res.status(401).json({
            success: false,
            error: {
                code: 'TOKEN_REFRESH_FAILED',
                message: error.message
            }
        });
    }
};
```

### 3.2 Chat Session Controller

```javascript
// src/controllers/chatSessionController.js
const chatService = require('../services/chatService');
const auditService = require('../services/auditService');
const logger = require('../utils/logger');
const { validationResult } = require('express-validator');

exports.createSession = async (req, res) => {
    try {
        const errors = validationResult(req);
        if (!errors.isEmpty()) {
            return res.status(400).json({
                success: false,
                error: {
                    code: 'VALIDATION_ERROR',
                    details: errors.array()
                }
            });
        }

        const session = await chatService.createSession(req.user.userId, req.body);

        await auditService.logAction(
            req.user.userId,
            session.id,
            'session_created',
            { sessionName: req.body.sessionName },
            req
        );

        res.status(201).json({
            success: true,
            data: session
        });
    } catch (error) {
        logger.error(`Create session error: ${error.message}`);
        res.status(400).json({
            success: false,
            error: {
                code: 'SESSION_CREATION_FAILED',
                message: error.message
            }
        });
    }
};

exports.getSessions = async (req, res) => {
    try {
        const { page = 1, limit = 10 } = req.query;
        const result = await chatService.getAllSessions(req.user.userId, { 
            page: parseInt(page), 
            limit: parseInt(limit) 
        });

        res.json({
            success: true,
            data: result.sessions,
            pagination: {
                total: result.total,
                page: result.page,
                limit: result.limit
            }
        });
    } catch (error) {
        logger.error(`Get sessions error: ${error.message}`);
        res.status(400).json({
            success: false,
            error: {
                code: 'GET_SESSIONS_FAILED',
                message: error.message
            }
        });
    }
};

exports.closeSession = async (req, res) => {
    try {
        const { sessionId } = req.params;
        const result = await chatService.closeSession(sessionId, req.user.userId);

        await auditService.logAction(
            req.user.userId,
            sessionId,
            'session_closed',
            {},
            req
        );

        res.json({
            success: true,
            data: result
        });
    } catch (error) {
        logger.error(`Close session error: ${error.message}`);
        res.status(400).json({
            success: false,
            error: {
                code: 'SESSION_CLOSURE_FAILED',
                message: error.message
            }
        });
    }
};
```

---

## 4. Middleware Implementation

### 4.1 Authentication Middleware

```javascript
// src/middleware/authMiddleware.js
const jwt = require('jsonwebtoken');
const logger = require('../utils/logger');

module.exports = (req, res, next) => {
    try {
        const authHeader = req.get('Authorization');

        if (!authHeader) {
            return res.status(401).json({
                success: false,
                error: {
                    code: 'MISSING_TOKEN',
                    message: 'Authorization token is required'
                }
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
        logger.error(`Auth middleware error: ${error.message}`);

        if (error.name === 'TokenExpiredError') {
            return res.status(401).json({
                success: false,
                error: {
                    code: 'TOKEN_EXPIRED',
                    message: 'Token has expired'
                }
            });
        }

        res.status(401).json({
            success: false,
            error: {
                code: 'INVALID_TOKEN',
                message: 'Invalid authorization token'
            }
        });
    }
};
```

### 4.2 Error Handler Middleware

```javascript
// src/middleware/errorHandler.js
const logger = require('../utils/logger');

module.exports = (err, req, res, next) => {
    logger.error(`Error: ${err.message}`);

    if (err.name === 'ValidationError') {
        return res.status(400).json({
            success: false,
            error: {
                code: 'VALIDATION_ERROR',
                message: err.message,
                details: err.details || []
            }
        });
    }

    if (err.name === 'UnauthorizedError') {
        return res.status(401).json({
            success: false,
            error: {
                code: 'UNAUTHORIZED',
                message: 'Unauthorized access'
            }
        });
    }

    res.status(err.status || 500).json({
        success: false,
        error: {
            code: err.code || 'INTERNAL_ERROR',
            message: err.message || 'An unexpected error occurred'
        }
    });
};
```

---

## 5. Error Handling

### Standard Error Codes

```javascript
// src/utils/errorCodes.js
const ERROR_CODES = {
    // Authentication
    INVALID_TOKEN: 'INVALID_TOKEN',
    TOKEN_EXPIRED: 'TOKEN_EXPIRED',
    UNAUTHORIZED: 'UNAUTHORIZED',
    FORBIDDEN: 'FORBIDDEN',

    // Validation
    VALIDATION_ERROR: 'VALIDATION_ERROR',
    INVALID_INPUT: 'INVALID_INPUT',

    // Resources
    NOT_FOUND: 'NOT_FOUND',
    ALREADY_EXISTS: 'ALREADY_EXISTS',

    // Operations
    OPERATION_FAILED: 'OPERATION_FAILED',
    SESSION_CLOSED: 'SESSION_CLOSED',

    // External Services
    AI_SERVICE_UNAVAILABLE: 'AI_SERVICE_UNAVAILABLE',
    EXTERNAL_SERVICE_ERROR: 'EXTERNAL_SERVICE_ERROR',

    // System
    INTERNAL_ERROR: 'INTERNAL_ERROR',
    RATE_LIMIT_EXCEEDED: 'RATE_LIMIT_EXCEEDED'
};

module.exports = ERROR_CODES;
```

---

## 6. Testing Strategy

### Unit Test Example

```javascript
// tests/unit/services/authService.test.js
const authService = require('../../../src/services/authService');

describe('AuthService', () => {
    describe('registerUser', () => {
        test('Should register a new user successfully', async () => {
            const userData = {
                email: 'test@example.com',
                username: 'testuser',
                password: 'password123',
                fullName: 'Test User'
            };

            const result = await authService.registerUser(userData);

            expect(result.user).toBeDefined();
            expect(result.accessToken).toBeDefined();
            expect(result.refreshToken).toBeDefined();
            expect(result.user.email).toBe(userData.email);
        });

        test('Should throw error if email already exists', async () => {
            const userData = {
                email: 'existing@example.com',
                username: 'newuser',
                password: 'password123'
            };

            await expect(authService.registerUser(userData))
                .rejects
                .toThrow('Email or username already exists');
        });
    });
});
```

### Integration Test Example

```javascript
// tests/integration/api/chat.test.js
const request = require('supertest');
const app = require('../../../src/app');

describe('Chat API', () => {
    let token;
    let sessionId;

    beforeAll(async () => {
        // Login and get token
        const res = await request(app)
            .post('/api/v1/auth/login')
            .send({
                email: 'test@example.com',
                password: 'password123'
            });

        token = res.body.data.token;
    });

    describe('POST /api/v1/chat/sessions', () => {
        test('Should create a new chat session', async () => {
            const res = await request(app)
                .post('/api/v1/chat/sessions')
                .set('Authorization', `Bearer ${token}`)
                .send({
                    sessionName: 'Test Session',
                    aiTeacherId: 'gemini-pro'
                });

            expect(res.status).toBe(201);
            expect(res.body.success).toBe(true);
            expect(res.body.data.id).toBeDefined();

            sessionId = res.body.data.id;
        });
    });

    describe('POST /api/v1/chat/messages', () => {
        test('Should send a message and get AI response', async () => {
            const res = await request(app)
                .post('/api/v1/chat/messages')
                .set('Authorization', `Bearer ${token}`)
                .send({
                    sessionId,
                    content: 'What is 2 + 2?'
                });

            expect(res.status).toBe(201);
            expect(res.body.success).toBe(true);
            expect(res.body.data.userMessage).toBeDefined();
            expect(res.body.data.aiResponse).toBeDefined();
        });
    });
});
```

---

## 7. Deployment Guide

### Pre-Deployment

1. **Environment Setup**
   ```bash
   cp .env.example .env
   # Edit .env with production values
   ```

2. **Database Migrations**
   ```bash
   npm run db:migrate
   ```

3. **Run Tests**
   ```bash
   npm run test
   npm run test:coverage
   ```

4. **Linting**
   ```bash
   npm run lint
   npm run lint:fix
   ```

### Docker Deployment

```dockerfile
# Dockerfile
FROM node:18-alpine

WORKDIR /app

COPY package*.json ./
RUN npm ci --only=production

COPY src ./src

EXPOSE 3000

CMD ["node", "src/app.js"]
```

```yaml
# docker-compose.yml
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
    depends_on:
      - postgres

  postgres:
    image: postgres:14-alpine
    environment:
      - POSTGRES_DB=ai_chat_db
      - POSTGRES_PASSWORD=secure_password
    volumes:
      - postgres_data:/var/lib/postgresql/data

volumes:
  postgres_data:
```

---

**Document Version**: 1.0  
**Created**: 2026-06-18  
**Last Modified**: 2026-06-18
