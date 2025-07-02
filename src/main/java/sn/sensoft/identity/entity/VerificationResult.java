package sn.sensoft.identity.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "verification_results")
public class VerificationResult {

    @Id
    @Column(columnDefinition = "uuid")
    @GeneratedValue(generator = "UUID")
    @org.hibernate.annotations.GenericGenerator(
            name = "UUID",
            strategy = "org.hibernate.id.UUIDGenerator"
    )
    private UUID id;

    @Column(name = "request_id", nullable = false, unique = true, length = 8)
    private String requestId;

    @Column(name = "session_id", length = 8)
    private String sessionId;

    @Column(name = "user_identifier", nullable = false)
    private String userIdentifier;

    // Résultats de la comparaison faciale
    @Column(name = "face_confidence_score", precision = 5, scale = 4)
    private BigDecimal faceConfidenceScore;

    @Column(name = "face_is_match")
    private Boolean faceIsMatch;

    @Column(name = "face_comparison_data", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> faceComparisonData;

    // Résultats de l'extraction de document
    @Column(name = "document_extraction_data", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> documentExtractionData;

    // Status et métadonnées
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VerificationStatus status = VerificationStatus.COMPLETED;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // Références aux fichiers utilisés
    @Column(name = "document_file_id")
    private UUID documentFileId;

    @Column(name = "photo_file_id")
    private UUID photoFileId;

    // Audit
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Constructeurs
    public VerificationResult() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public VerificationResult(String requestId, String userIdentifier) {
        this();
        this.requestId = requestId;
        this.userIdentifier = userIdentifier;
    }

    // Méthodes business
    public boolean isSuccessful() {
        return status == VerificationStatus.COMPLETED && faceIsMatch != null;
    }

    public boolean hasFaceMatch() {
        return Boolean.TRUE.equals(faceIsMatch);
    }

    public double getConfidenceAsDouble() {
        return faceConfidenceScore != null ? faceConfidenceScore.doubleValue() : 0.0;
    }

    // Factory methods
    public static VerificationResult createSuccess(String requestId, String userIdentifier,
                                                   String sessionId, Double confidence, Boolean isMatch) {
        VerificationResult result = new VerificationResult(requestId, userIdentifier);
        result.setSessionId(sessionId);
        result.setFaceConfidenceScore(BigDecimal.valueOf(confidence));
        result.setFaceIsMatch(isMatch);
        result.setStatus(VerificationStatus.COMPLETED);
        result.setMessage(isMatch ? "Visages correspondent" : "Visages ne correspondent pas");
        return result;
    }

    public static VerificationResult createError(String requestId, String userIdentifier,
                                                 String errorMessage) {
        VerificationResult result = new VerificationResult(requestId, userIdentifier);
        result.setStatus(VerificationStatus.FAILED);
        result.setErrorMessage(errorMessage);
        return result;
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

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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

    public BigDecimal getFaceConfidenceScore() {
        return faceConfidenceScore;
    }

    public void setFaceConfidenceScore(BigDecimal faceConfidenceScore) {
        this.faceConfidenceScore = faceConfidenceScore;
    }

    public Boolean getFaceIsMatch() {
        return faceIsMatch;
    }

    public void setFaceIsMatch(Boolean faceIsMatch) {
        this.faceIsMatch = faceIsMatch;
    }

    public Map<String, Object> getFaceComparisonData() {
        return faceComparisonData;
    }

    public void setFaceComparisonData(Map<String, Object> faceComparisonData) {
        this.faceComparisonData = faceComparisonData;
    }

    public Map<String, Object> getDocumentExtractionData() {
        return documentExtractionData;
    }

    public void setDocumentExtractionData(Map<String, Object> documentExtractionData) {
        this.documentExtractionData = documentExtractionData;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public void setStatus(VerificationStatus status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public UUID getDocumentFileId() {
        return documentFileId;
    }

    public void setDocumentFileId(UUID documentFileId) {
        this.documentFileId = documentFileId;
    }

    public UUID getPhotoFileId() {
        return photoFileId;
    }

    public void setPhotoFileId(UUID photoFileId) {
        this.photoFileId = photoFileId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Enum pour le statut de vérification
    public enum VerificationStatus {
        PROCESSING,
        COMPLETED,
        FAILED
    }
}