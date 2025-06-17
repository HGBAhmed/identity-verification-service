CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE verification_requests (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_identifier VARCHAR(255) NOT NULL,
    identity_document_path VARCHAR(500),
    user_photo_path VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    confidence_score DECIMAL(5,4),
    is_match BOOLEAN,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_verification_user_identifier ON verification_requests(user_identifier);
CREATE INDEX idx_verification_status ON verification_requests(status);
CREATE INDEX idx_verification_created_at ON verification_requests(created_at);
