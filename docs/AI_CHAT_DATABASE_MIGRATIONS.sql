-- AI Chat Feature - Database Migrations
-- Version: 1.0
-- Created: 2026-06-18
-- Purpose: Complete database schema for AI chat system

-- ============================================
-- 1. CREATE CUSTOM DATA TYPES
-- ============================================

CREATE TYPE user_role_enum AS ENUM ('student', 'teacher', 'admin');
CREATE TYPE message_type_enum AS ENUM ('user_message', 'ai_response');
CREATE TYPE chat_status_enum AS ENUM ('active', 'archived', 'closed');
CREATE TYPE audit_action_enum AS ENUM (
    'session_created',
    'session_closed',
    'message_sent',
    'message_deleted',
    'session_accessed',
    'session_exported'
);

-- ============================================
-- 2. CREATE TABLES
-- ============================================

-- Users Table
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    username VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    phone_number VARCHAR(20),
    user_role user_role_enum DEFAULT 'student',
    is_active BOOLEAN DEFAULT true,
    is_email_verified BOOLEAN DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP WITH TIME ZONE,
    profile_image_url VARCHAR(500),
    metadata JSONB DEFAULT '{}'::jsonb,
    CONSTRAINT valid_email CHECK (email ~ '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}$')
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_is_active ON users(is_active);
CREATE INDEX idx_users_user_role ON users(user_role);
CREATE INDEX idx_users_created_at ON users(created_at);

-- Chat Sessions Table
CREATE TABLE IF NOT EXISTS chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_name VARCHAR(255),
    description TEXT,
    ai_teacher_id VARCHAR(255) NOT NULL,
    ai_model VARCHAR(100) DEFAULT 'gemini-pro',
    status chat_status_enum DEFAULT 'active',
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_message_at TIMESTAMP WITH TIME ZONE,
    closed_at TIMESTAMP WITH TIME ZONE,
    total_messages INT DEFAULT 0,
    total_user_messages INT DEFAULT 0,
    total_ai_responses INT DEFAULT 0,
    duration_seconds INT DEFAULT 0,
    topic VARCHAR(255),
    tags JSONB DEFAULT '[]'::jsonb,
    metadata JSONB DEFAULT '{}'::jsonb
);

CREATE INDEX idx_chat_sessions_user_id ON chat_sessions(user_id);
CREATE INDEX idx_chat_sessions_created_at ON chat_sessions(created_at);
CREATE INDEX idx_chat_sessions_is_active ON chat_sessions(is_active);
CREATE INDEX idx_chat_sessions_status ON chat_sessions(status);
CREATE INDEX idx_chat_sessions_ai_model ON chat_sessions(ai_model);

-- Chat Messages Table
CREATE TABLE IF NOT EXISTS chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message_type message_type_enum NOT NULL,
    content TEXT NOT NULL,
    encrypted_content TEXT,
    is_encrypted BOOLEAN DEFAULT false,
    encryption_key_id VARCHAR(100),
    message_timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP WITH TIME ZONE,
    flagged_for_review BOOLEAN DEFAULT false,
    flag_reason VARCHAR(500),
    flag_severity VARCHAR(50), -- low, medium, high, critical
    flagged_at TIMESTAMP WITH TIME ZONE,
    flagged_by UUID REFERENCES users(id),
    metadata JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT message_content_not_empty CHECK (length(trim(content)) > 0)
);

CREATE INDEX idx_chat_messages_session_id ON chat_messages(session_id);
CREATE INDEX idx_chat_messages_user_id ON chat_messages(user_id);
CREATE INDEX idx_chat_messages_message_type ON chat_messages(message_type);
CREATE INDEX idx_chat_messages_timestamp ON chat_messages(message_timestamp);
CREATE INDEX idx_chat_messages_flagged ON chat_messages(flagged_for_review);
CREATE INDEX idx_chat_messages_created_at ON chat_messages(created_at);

-- AI Response Metadata Table
CREATE TABLE IF NOT EXISTS ai_response_metadata (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    ai_model VARCHAR(100) NOT NULL,
    model_version VARCHAR(50),
    confidence_score DECIMAL(3,2),
    processing_time_ms INT,
    tokens_used INT,
    input_tokens INT,
    output_tokens INT,
    api_call_id VARCHAR(255),
    api_provider VARCHAR(50), -- gemini, openai, etc
    request_metadata JSONB DEFAULT '{}'::jsonb,
    response_metadata JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ai_response_message_id ON ai_response_metadata(message_id);
CREATE INDEX idx_ai_response_session_id ON ai_response_metadata(session_id);
CREATE INDEX idx_ai_response_ai_model ON ai_response_metadata(ai_model);

-- Chat History Summary Table
CREATE TABLE IF NOT EXISTS chat_history_summary (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL UNIQUE REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_summary TEXT,
    keywords JSONB DEFAULT '[]'::jsonb,
    topics JSONB DEFAULT '[]'::jsonb,
    total_user_messages INT,
    total_ai_responses INT,
    conversation_duration_seconds INT,
    session_start_time TIMESTAMP WITH TIME ZONE,
    session_end_time TIMESTAMP WITH TIME ZONE,
    learning_objectives JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chat_history_session_id ON chat_history_summary(session_id);
CREATE INDEX idx_chat_history_user_id ON chat_history_summary(user_id);

-- Chat Audit Log Table
CREATE TABLE IF NOT EXISTS chat_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES chat_sessions(id) ON DELETE SET NULL,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action audit_action_enum NOT NULL,
    action_details JSONB DEFAULT '{}'::jsonb,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    status VARCHAR(50), -- success, failure, partial
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_log_user_id ON chat_audit_log(user_id);
CREATE INDEX idx_audit_log_created_at ON chat_audit_log(created_at);
CREATE INDEX idx_audit_log_action ON chat_audit_log(action);
CREATE INDEX idx_audit_log_session_id ON chat_audit_log(session_id);

-- User Preferences Table
CREATE TABLE IF NOT EXISTS user_chat_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    preferred_ai_model VARCHAR(100) DEFAULT 'gemini-pro',
    auto_save_enabled BOOLEAN DEFAULT true,
    notifications_enabled BOOLEAN DEFAULT true,
    show_ai_metadata BOOLEAN DEFAULT false,
    theme VARCHAR(50) DEFAULT 'light',
    language VARCHAR(10) DEFAULT 'en',
    allow_data_collection BOOLEAN DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_preferences_user_id ON user_chat_preferences(user_id);

-- User Session Tokens Table (for refresh tokens)
CREATE TABLE IF NOT EXISTS user_session_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token VARCHAR(500) NOT NULL UNIQUE,
    access_token_jti VARCHAR(255), -- JWT ID for revocation
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_session_tokens_user_id ON user_session_tokens(user_id);
CREATE INDEX idx_session_tokens_expires_at ON user_session_tokens(expires_at);
CREATE INDEX idx_session_tokens_refresh_token ON user_session_tokens(refresh_token);

-- ============================================
-- 3. CREATE VIEWS
-- ============================================

-- User Chat Statistics View
CREATE OR REPLACE VIEW vw_user_chat_statistics AS
SELECT
    u.id,
    u.email,
    u.username,
    u.full_name,
    COUNT(DISTINCT cs.id) as total_sessions,
    COUNT(DISTINCT cm.id) as total_messages,
    SUM(CASE WHEN cm.message_type = 'user_message' THEN 1 ELSE 0 END) as user_messages,
    SUM(CASE WHEN cm.message_type = 'ai_response' THEN 1 ELSE 0 END) as ai_responses,
    COALESCE(MAX(cm.created_at), u.created_at) as last_active,
    COALESCE(SUM(cs.duration_seconds), 0) as total_chat_duration_seconds
FROM users u
LEFT JOIN chat_sessions cs ON u.id = cs.user_id
LEFT JOIN chat_messages cm ON cs.id = cm.session_id
GROUP BY u.id, u.email, u.username, u.full_name;

-- Active Sessions View
CREATE OR REPLACE VIEW vw_active_chat_sessions AS
SELECT
    cs.id,
    cs.user_id,
    u.email,
    cs.session_name,
    cs.ai_model,
    cs.created_at,
    cs.last_message_at,
    cs.total_messages,
    EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - cs.created_at))::INTEGER as duration_seconds
FROM chat_sessions cs
INNER JOIN users u ON cs.user_id = u.id
WHERE cs.is_active = true
ORDER BY cs.last_message_at DESC;

-- Flagged Messages View
CREATE OR REPLACE VIEW vw_flagged_messages AS
SELECT
    cm.id,
    cm.session_id,
    cs.session_name,
    cm.user_id,
    u.email,
    cm.content,
    cm.flag_reason,
    cm.flag_severity,
    cm.flagged_at,
    cm.flagged_by
FROM chat_messages cm
INNER JOIN chat_sessions cs ON cm.session_id = cs.id
INNER JOIN users u ON cm.user_id = u.id
WHERE cm.flagged_for_review = true
ORDER BY cm.flagged_at DESC;

-- ============================================
-- 4. CREATE FUNCTIONS
-- ============================================

-- Function to update user's last_login timestamp
CREATE OR REPLACE FUNCTION update_user_last_login()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE users SET last_login = CURRENT_TIMESTAMP WHERE id = NEW.user_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Function to update timestamps
CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Function to auto-calculate session duration
CREATE OR REPLACE FUNCTION update_session_duration()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE chat_sessions
    SET duration_seconds = EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - created_at))::INTEGER,
        last_message_at = CURRENT_TIMESTAMP
    WHERE id = NEW.session_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Function to validate message content
CREATE OR REPLACE FUNCTION validate_message_content()
RETURNS TRIGGER AS $$
BEGIN
    IF trim(NEW.content) = '' THEN
        RAISE EXCEPTION 'Message content cannot be empty';
    END IF;
    IF length(NEW.content) > 10000 THEN
        RAISE EXCEPTION 'Message content exceeds maximum length of 10000 characters';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- 5. CREATE TRIGGERS
-- ============================================

-- Trigger to update timestamp on user update
CREATE TRIGGER trigger_update_users_timestamp
BEFORE UPDATE ON users
FOR EACH ROW
EXECUTE FUNCTION update_timestamp();

-- Trigger to update timestamp on chat_sessions update
CREATE TRIGGER trigger_update_chat_sessions_timestamp
BEFORE UPDATE ON chat_sessions
FOR EACH ROW
EXECUTE FUNCTION update_timestamp();

-- Trigger to update timestamp on chat_messages update
CREATE TRIGGER trigger_update_chat_messages_timestamp
BEFORE UPDATE ON chat_messages
FOR EACH ROW
EXECUTE FUNCTION update_timestamp();

-- Trigger to validate message content
CREATE TRIGGER trigger_validate_message_content
BEFORE INSERT OR UPDATE ON chat_messages
FOR EACH ROW
EXECUTE FUNCTION validate_message_content();

-- Trigger to update session duration on message insert
CREATE TRIGGER trigger_update_session_duration
AFTER INSERT ON chat_messages
FOR EACH ROW
EXECUTE FUNCTION update_session_duration();

-- Trigger to log user login
CREATE TRIGGER trigger_log_user_login
AFTER UPDATE ON users
FOR EACH ROW
WHEN (OLD.last_login IS DISTINCT FROM NEW.last_login)
EXECUTE FUNCTION update_user_last_login();

-- ============================================
-- 6. CREATE INDEXES FOR PERFORMANCE
-- ============================================

-- Composite indexes for common queries
CREATE INDEX idx_chat_messages_session_timestamp ON chat_messages(session_id, message_timestamp);
CREATE INDEX idx_chat_sessions_user_created ON chat_sessions(user_id, created_at);
CREATE INDEX idx_audit_log_user_action_date ON chat_audit_log(user_id, action, created_at);
CREATE INDEX idx_ai_response_model_tokens ON ai_response_metadata(ai_model, tokens_used);

-- Full-text search index for message content (optional)
CREATE INDEX idx_chat_messages_content_search ON chat_messages USING gin(to_tsvector('english', content));

-- ============================================
-- 7. CREATE PARTITIONING FOR LARGE TABLES (Optional - for production)
-- ============================================

-- Comment: For large-scale production, consider partitioning chat_messages and audit_log by date
-- This improves query performance and allows for better maintenance

/*
-- Uncomment and adapt for your production environment
-- Partition chat_messages by month
CREATE TABLE chat_messages_2026_06 PARTITION OF chat_messages
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

-- Add more partitions as needed
*/

-- ============================================
-- 8. INITIAL DATA SETUP
-- ============================================

-- Insert admin user (change password in production)
INSERT INTO users (email, username, password_hash, full_name, user_role, is_email_verified)
VALUES (
    'admin@example.com',
    'admin',
    '$2b$12$1234567890123456789012345678901234567890', -- Replace with actual bcrypt hash
    'Admin User',
    'admin',
    true
) ON CONFLICT (email) DO NOTHING;

-- Grant appropriate permissions (if using row-level security)
-- ALTER TABLE users ENABLE ROW LEVEL SECURITY;
-- CREATE POLICY enable_read_own_user ON users FOR SELECT USING (auth.uid() = id);

-- ============================================
-- 9. BACKUP AND RECOVERY
-- ============================================

/*
-- Backup command (run manually or in cron job)
pg_dump -U postgres -h localhost database_name > backup_$(date +%Y%m%d_%H%M%S).sql

-- Restore command
psql -U postgres -h localhost database_name < backup_file.sql
*/

-- ============================================
-- Document Version: 1.0
-- Created: 2026-06-18
-- Last Modified: 2026-06-18
-- ============================================
