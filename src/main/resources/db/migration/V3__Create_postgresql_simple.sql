-- Extension pour UUID
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ===================================
-- TABLE : Sessions de vérification
-- ===================================
CREATE TABLE verification_sessions (
                                       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                       session_id VARCHAR(8) NOT NULL UNIQUE,
                                       user_identifier VARCHAR(255) NOT NULL,
                                       status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- Données du document
                                       document_type VARCHAR(100),
                                       issuing_country VARCHAR(100),
                                       extracted_data JSONB,  -- Données JSON natives PostgreSQL

    -- Timestamps
                                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                       expires_at TIMESTAMP DEFAULT (CURRENT_TIMESTAMP + INTERVAL '30 minutes'),
                                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ===================================
-- TABLE : Fichiers
-- ===================================
CREATE TABLE verification_files (
                                    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                    session_id VARCHAR(8) NOT NULL,
                                    file_type VARCHAR(20) NOT NULL,

    -- Références OpenKM
                                    openkm_uuid VARCHAR(255) NOT NULL,
                                    openkm_path VARCHAR(500) NOT NULL,
                                    openkm_folder VARCHAR(255) NOT NULL,

    -- Métadonnées
                                    original_filename VARCHAR(255),
                                    content_type VARCHAR(100),
                                    file_size BIGINT,

    -- Temporaire
                                    temp_path VARCHAR(500),
                                    temp_expires_at TIMESTAMP,

                                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                                    CONSTRAINT fk_files_session FOREIGN KEY (session_id)
                                        REFERENCES verification_sessions(session_id) ON DELETE CASCADE
);

-- ===================================
-- TABLE : Résultats de vérification
-- ===================================
CREATE TABLE verification_results (
                                      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                      request_id VARCHAR(8) NOT NULL UNIQUE,
                                      session_id VARCHAR(8),
                                      user_identifier VARCHAR(255) NOT NULL,

    -- Résultats comparaison faciale
                                      face_confidence_score DECIMAL(5,4),
                                      face_is_match BOOLEAN,
                                      face_comparison_data JSONB,  -- JSON natif PostgreSQL

    -- Extraction document
                                      document_extraction_data JSONB,  -- JSON natif PostgreSQL

    -- Status
                                      status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
                                      message TEXT,
                                      error_message TEXT,

    -- Références fichiers
                                      document_file_id UUID,
                                      photo_file_id UUID,

    -- Audit
                                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                                      CONSTRAINT fk_results_session FOREIGN KEY (session_id)
                                          REFERENCES verification_sessions(session_id) ON DELETE SET NULL,
                                      CONSTRAINT fk_results_document_file FOREIGN KEY (document_file_id)
                                          REFERENCES verification_files(id) ON DELETE SET NULL,
                                      CONSTRAINT fk_results_photo_file FOREIGN KEY (photo_file_id)
                                          REFERENCES verification_files(id) ON DELETE SET NULL
);

-- ===================================
-- INDEX ESSENTIELS
-- ===================================
CREATE INDEX idx_sessions_session_id ON verification_sessions(session_id);
CREATE INDEX idx_sessions_user_identifier ON verification_sessions(user_identifier);
CREATE INDEX idx_sessions_status ON verification_sessions(status);
CREATE INDEX idx_sessions_expires_at ON verification_sessions(expires_at);

CREATE INDEX idx_files_session_id ON verification_files(session_id);
CREATE INDEX idx_files_file_type ON verification_files(file_type);
CREATE INDEX idx_files_openkm_uuid ON verification_files(openkm_uuid);

CREATE INDEX idx_results_request_id ON verification_results(request_id);
CREATE INDEX idx_results_session_id ON verification_results(session_id);
CREATE INDEX idx_results_user_identifier ON verification_results(user_identifier);
CREATE INDEX idx_results_status ON verification_results(status);

-- Index GIN pour les recherches JSONB
CREATE INDEX idx_sessions_extracted_data_gin ON verification_sessions USING GIN(extracted_data);
CREATE INDEX idx_results_face_comparison_gin ON verification_results USING GIN(face_comparison_data);
CREATE INDEX idx_results_document_extraction_gin ON verification_results USING GIN(document_extraction_data);