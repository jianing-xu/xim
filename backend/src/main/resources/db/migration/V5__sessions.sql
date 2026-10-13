CREATE TABLE xim_sessions (
    session_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES xim_users(user_id),
    credential_version BIGINT NOT NULL,
    token_hash VARCHAR(64) UNIQUE,
    ticket_hash VARCHAR(64) UNIQUE,
    ticket_expires_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    revoked INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX xim_sessions_user ON xim_sessions(user_id);
