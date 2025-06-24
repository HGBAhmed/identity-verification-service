package sn.sensoft.identity.dto;

import io.micronaut.serde.annotation.Serdeable;
import sn.sensoft.identity.dto.VerificationResultDto.DocumentExtractionData;
import java.time.LocalDateTime;

@Serdeable
public class DocumentUploadResponse {

    private String documentId;
    private String status;
    private String message;
    private DocumentExtractionData documentData;
    private LocalDateTime createdAt;

    public DocumentUploadResponse() {
        this.createdAt = LocalDateTime.now();
    }

    public static DocumentUploadResponse success(String documentId, DocumentExtractionData documentData) {
        DocumentUploadResponse response = new DocumentUploadResponse();
        response.documentId = documentId;
        response.status = "DOCUMENT_PROCESSED";
        response.documentData = documentData;
        response.message = "Document analysé avec succès. Vous pouvez maintenant ajouter votre photo.";
        return response;
    }

    public static DocumentUploadResponse error(String errorMessage) {
        DocumentUploadResponse response = new DocumentUploadResponse();
        response.status = "DOCUMENT_ERROR";
        response.message = errorMessage;
        return response;
    }

    // Getters et Setters
    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public DocumentExtractionData getDocumentData() {
        return documentData;
    }

    public void setDocumentData(DocumentExtractionData documentData) {
        this.documentData = documentData;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }


}