package sn.sensoft.identity.dto;

import java.time.LocalDateTime;
import java.util.Map;

public class DocumentSession {

    private String documentId;
    private String userIdentifier;
    private String documentPath;
    private String documentType;
    private String issuingCountry;
    private Map<String, Object> extractedData;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    public DocumentSession() {
        this.createdAt = LocalDateTime.now();
        this.expiresAt = LocalDateTime.now().plusMinutes(30); // TTL de 30 minutes
    }

    public DocumentSession(String documentId, String userIdentifier, String documentPath,
                           String documentType, String issuingCountry, Map<String, Object> extractedData) {
        this();
        this.documentId = documentId;
        this.userIdentifier = userIdentifier;
        this.documentPath = documentPath;
        this.documentType = documentType;
        this.issuingCountry = issuingCountry;
        this.extractedData = extractedData;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !isExpired() && documentPath != null && documentId != null;
    }

    // Getters et Setters
    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getUserIdentifier() {
        return userIdentifier;
    }

    public void setUserIdentifier(String userIdentifier) {
        this.userIdentifier = userIdentifier;
    }

    public String getDocumentPath() {
        return documentPath;
    }

    public void setDocumentPath(String documentPath) {
        this.documentPath = documentPath;
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
}