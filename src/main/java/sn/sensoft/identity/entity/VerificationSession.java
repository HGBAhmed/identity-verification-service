package sn.sensoft.identity.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "verification_sessions")
public class VerificationSession {

    @Id
    @Column(columnDefinition = "uuid")
    @GeneratedValue(generator = "UUID")
    @org.hibernate.annotations.GenericGenerator(
            name = "UUID",
            strategy = "org.hibernate.id.UUIDGenerator"
    )
    private UUID id;

    @Column(name = "session_id", nullable = false, unique = true, length = 8)
    private String sessionId;

    @Column(name = "user_identifier", nullable = false)
    private String userIdentifier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SessionStatus status = SessionStatus.PENDING;

    @Column(name = "document_type", length = 100)
    private String documentType;

    @Column(name = "issuing_country", length = 100)
    private String issuingCountry;

    // PostgreSQL JSONB natif
    @Column(name = "extracted_data", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> extractedData;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Relation avec les fichiers
    @OneToMany(mappedBy = "sessionId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<VerificationFile> files = new ArrayList<>();

    // Constructeurs
    public VerificationSession() {
        this.createdAt = LocalDateTime.now();
        this.expiresAt = LocalDateTime.now().plusMinutes(30);
        this.updatedAt = LocalDateTime.now();
    }

    public VerificationSession(String sessionId, String userIdentifier) {
        this();
        this.sessionId = sessionId;
        this.userIdentifier = userIdentifier;
    }

    // Méthodes business
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !isExpired() && sessionId != null && userIdentifier != null;
    }

    public void extendExpiration(int minutes) {
        this.expiresAt = LocalDateTime.now().plusMinutes(minutes);
        this.updatedAt = LocalDateTime.now();
    }

    // Callbacks JPA
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters & Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserIdentifier() {
        return userIdentifier;
    }

    public void setUserIdentifier(String userIdentifier) {
        this.userIdentifier = userIdentifier;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public String getDocumentType() {
        return documentType;
    }

    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    public String getIssuingCountry() {
        return issuingCountry;
    }

    public void setIssuingCountry(String issuingCountry) {
        this.issuingCountry = issuingCountry;
    }

    public Map<String, Object> getExtractedData() {
        return extractedData;
    }

    public void setExtractedData(Map<String, Object> extractedData) {
        this.extractedData = extractedData;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<VerificationFile> getFiles() {
        return files;
    }

    public void setFiles(List<VerificationFile> files) {
        this.files = files;
    }

    // Méthodes utilitaires pour les fichiers
    public void addFile(VerificationFile file) {
        files.add(file);
        file.setSessionId(this.sessionId);
    }

    public VerificationFile getFileByType(FileType fileType) {
        return files.stream()
                .filter(f -> f.getFileType() == fileType)
                .findFirst()
                .orElse(null);
    }

    // Enums
    public enum SessionStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        EXPIRED
    }
}