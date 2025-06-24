package sn.sensoft.identity.entity;

import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.DateUpdated;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.model.naming.NamingStrategies;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "verification_requests")
@MappedEntity(namingStrategy = NamingStrategies.UnderScoreSeparatedLowerCase.class)
public class VerificationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "user_identifier")
    private String userIdentifier;

    @Column(name = "identity_document_path")
    private String identityDocumentPath;

    @Column(name = "user_photo_path")
    private String userPhotoPath;

    @Column(name = "status")
    private String status;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "is_match")
    private Boolean isMatch;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @DateCreated
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @DateUpdated
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Constructeurs
    public VerificationRequest() {}

    public VerificationRequest(String userIdentifier) {
        this.userIdentifier = userIdentifier;
        this.status = "PENDING";
    }

    // Getters et Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getUserIdentifier() { return userIdentifier; }
    public void setUserIdentifier(String userIdentifier) { this.userIdentifier = userIdentifier; }

    public String getIdentityDocumentPath() { return identityDocumentPath; }
    public void setIdentityDocumentPath(String identityDocumentPath) { this.identityDocumentPath = identityDocumentPath; }

    public String getUserPhotoPath() { return userPhotoPath; }
    public void setUserPhotoPath(String userPhotoPath) { this.userPhotoPath = userPhotoPath; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public Boolean getIsMatch() { return isMatch; }
    public void setIsMatch(Boolean isMatch) { this.isMatch = isMatch; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}