package sn.sensoft.identity.dto;

import io.micronaut.serde.annotation.Serdeable;
import java.time.LocalDateTime;
import java.util.Map;

@Serdeable
public class VerificationResultDto {

    private String requestId; // Changé de UUID à String
    private String userIdentifier;
    private String status;
    private Double confidenceScore;
    private Boolean isMatch;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Nouvelles propriétés pour l'extraction de document
    private FaceComparisonData faceComparison;
    private DocumentExtractionData documentData;

    public VerificationResultDto() {}

    public VerificationResultDto(String requestId, String userIdentifier, String status) { // UUID à String
        this.requestId = requestId;
        this.userIdentifier = userIdentifier;
        this.status = status;
    }

    public static VerificationResultDto success(String requestId, String userIdentifier, //  UUID à String
                                                Double confidence, Boolean isMatch) {
        VerificationResultDto dto = new VerificationResultDto(requestId, userIdentifier, "COMPLETED");
        dto.confidenceScore = confidence;
        dto.isMatch = isMatch;
        dto.message = isMatch ? "Visages correspondent" : "Visages ne correspondent pas";
        return dto;
    }

    public static VerificationResultDto successWithData(String requestId, String userIdentifier, // Changé UUID à String
                                                        Double faceConfidence, Boolean isMatch,
                                                        Map<String, Object> documentExtractedData,
                                                        String documentType, String issuingCountry) {
        VerificationResultDto dto = new VerificationResultDto(requestId, userIdentifier, "COMPLETED");

        // Données de comparaison faciale
        FaceComparisonData faceData = new FaceComparisonData();
        faceData.setConfidenceScore(faceConfidence);
        faceData.setIsMatch(isMatch);
        faceData.setMessage(isMatch ? "Visages correspondent" : "Visages ne correspondent pas");
        dto.setFaceComparison(faceData);

        // Données d'extraction de document
        DocumentExtractionData docData = new DocumentExtractionData();
        docData.setDocumentType(documentType);
        docData.setIssuingCountry(issuingCountry);
        docData.setExtractedFields(documentExtractedData);
        dto.setDocumentData(docData);

        dto.message = "Vérification complète réussie";
        return dto;
    }

    public static VerificationResultDto error(String errorMessage) {
        VerificationResultDto dto = new VerificationResultDto();
        dto.status = "FAILED";
        dto.message = errorMessage;
        return dto;
    }

    public static VerificationResultDto processing(String requestId, String userIdentifier) { //  UUID à String
        return new VerificationResultDto(requestId, userIdentifier, "PROCESSING");
    }

    // Classes internes pour structurer les données
    @Serdeable
    public static class FaceComparisonData {
        private Double confidenceScore;
        private Boolean isMatch;
        private String message;

        public Double getConfidenceScore() { return confidenceScore; }
        public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

        public Boolean getIsMatch() { return isMatch; }
        public void setIsMatch(Boolean isMatch) { this.isMatch = isMatch; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    @Serdeable
    public static class DocumentExtractionData {
        private String documentType;
        private String issuingCountry;
        private Map<String, Object> extractedFields;

        public String getDocumentType() { return documentType; }
        public void setDocumentType(String documentType) { this.documentType = documentType; }

        public String getIssuingCountry() { return issuingCountry; }
        public void setIssuingCountry(String issuingCountry) { this.issuingCountry = issuingCountry; }

        public Map<String, Object> getExtractedFields() { return extractedFields; }
        public void setExtractedFields(Map<String, Object> extractedFields) { this.extractedFields = extractedFields; }
    }

    // Getters et Setters existants
    public String getRequestId() { return requestId; } // Changé UUID à String
    public void setRequestId(String requestId) { this.requestId = requestId; }

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

    // getters/setters Pour la comparaison et l'xtraction
    public FaceComparisonData getFaceComparison() { return faceComparison; }
    public void setFaceComparison(FaceComparisonData faceComparison) { this.faceComparison = faceComparison; }

    public DocumentExtractionData getDocumentData() { return documentData; }
    public void setDocumentData(DocumentExtractionData documentData) { this.documentData = documentData; }
}