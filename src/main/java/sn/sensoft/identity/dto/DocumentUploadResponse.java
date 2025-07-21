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

    //  MÉTHODES FACTory

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

    /**
     * Réponse de succès aux PASSEPORTS
     */
    public static DocumentUploadResponse successPassport(String documentId, DocumentExtractionData documentData) {
        DocumentUploadResponse response = success(documentId, documentData);
        response.message = "Passeport analysé avec succès. Vous pouvez maintenant ajouter votre photo pour la comparaison faciale.";
        return response;
    }

    /**
     * Réponse de succès aux CARTES D'IDENTITÉ
     */
    public static DocumentUploadResponse successIdCard(String documentId, DocumentExtractionData documentData, String side) {
        DocumentUploadResponse response = success(documentId, documentData);

        // Message personnalisé
        switch (side) {
            case "RECTO":
                response.message = "Recto de la carte d'identité analysé avec succès. " +
                        "Vous pouvez ajouter le verso pour une extraction complète ou procéder à la comparaison faciale.";
                break;
            case "VERSO":
                response.message = "Verso de la carte d'identité analysé avec succès. " +
                        "Données MRZ extraites. Vous pouvez maintenant ajouter votre photo.";
                break;
            case "BOTH":
                response.message = "Carte d'identité recto/verso analysée avec succès. " +
                        "Extraction complète réalisée. Vous pouvez maintenant ajouter votre photo.";
                break;
            default:
                response.message = "Carte d'identité analysée avec succès. Vous pouvez maintenant ajouter votre photo.";
        }

        return response;
    }

    /**
     * Réponse de succès pour carte d'identité française
     */
    public static DocumentUploadResponse successFrenchIdCard(String documentId, DocumentExtractionData documentData, String side) {
        return successIdCard(documentId, documentData, side);
    }

    /**
     * Réponse de succès pour carte d'identité sénégalaise
     */
    public static DocumentUploadResponse successSenegalIdCard(String documentId, DocumentExtractionData documentData, String side) {
        return successIdCard(documentId, documentData, side);
    }

    /**
     * Réponse d'erreur avec type de document spécifié
     */
    public static DocumentUploadResponse errorWithType(String errorMessage, String expectedType) {
        return error(errorMessage);
    }

    // GETTERS ET SETTERS

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

    @Override
    public String toString() {
        return "DocumentUploadResponse{" +
                "documentId='" + documentId + '\'' +
                ", status='" + status + '\'' +
                ", message='" + message + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}