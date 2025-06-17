package sn.sensoft.identity.dto;

import io.micronaut.serde.annotation.Serdeable;
import java.time.LocalDateTime;
import java.util.UUID;

@Serdeable
public class VerificationResultDto {

    private UUID requestId;
    private String userIdentifier;
    private String status;
    private Double confidenceScore;
    private Boolean isMatch;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public VerificationResultDto() {}

    public VerificationResultDto(UUID requestId, String userIdentifier, String status) {
        this.requestId = requestId;
        this.userIdentifier = userIdentifier;
        this.status = status;
    }

    public static VerificationResultDto success(UUID requestId, String userIdentifier,
                                                Double confidence, Boolean isMatch) {
        VerificationResultDto dto = new VerificationResultDto(requestId, userIdentifier, "COMPLETED");
        dto.confidenceScore = confidence;
        dto.isMatch = isMatch;
        dto.message = isMatch ? "Visages correspondent" : "Visages ne correspondent pas";
        return dto;
    }

    public static VerificationResultDto error(String errorMessage) {
        VerificationResultDto dto = new VerificationResultDto();
        dto.status = "FAILED";
        dto.message = errorMessage;
        return dto;
    }

    public static VerificationResultDto processing(UUID requestId, String userIdentifier) {
        return new VerificationResultDto(requestId, userIdentifier, "PROCESSING");
    }

    // Getters et Setters
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }

    public String getUserIdentifier() { return userIdentifier; }
    public void setUserIdentifier(String userIdentifier) { this.userIdentifier = userIdentifier; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public Boolean getIsMatch() { return isMatch; }
    public void setIsMatch(Boolean isMatch) { this.isMatch = isMatch; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}