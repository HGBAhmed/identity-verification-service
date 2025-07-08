package sn.sensoft.identity.service;

import sn.sensoft.identity.dto.VerificationResultDto;
import sn.sensoft.identity.entity.FileType;
import sn.sensoft.identity.entity.VerificationFile;
import sn.sensoft.identity.entity.VerificationSession;
import sn.sensoft.identity.util.FileValidator;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.util.UUID;

@Singleton
public class IdentityVerificationService {

    @Inject
    private FileStorageService fileStorageService;

    @Inject
    private FaceComparisonService faceComparisonService;

    @Inject
    private DocumentExtractionService documentExtractionService;

    @Inject
    private FileValidator fileValidator;

    @Inject
    private VerificationSessionService sessionService;

    @Inject
    private VerificationResultService resultService;

    @Transactional
    public VerificationResultDto processVerification(String userIdentifier,
                                                     CompletedFileUpload identityDocument,
                                                     CompletedFileUpload userPhoto) {
        String sessionId = null;
        UUID documentFileId = null;
        UUID photoFileId = null;

        try {
            // VALIDATION DES FICHIERS
            String docValidation = fileValidator.getValidationError(identityDocument);
            if (docValidation != null) {
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Document d'identité: " + docValidation);
                errorResult.setRequestId(requestId);

                // Sauvegarder l'erreur
                resultService.saveErrorResult(requestId, userIdentifier, "Validation document: " + docValidation, null);

                return errorResult;
            }

            String photoValidation = fileValidator.getValidationError(userPhoto);
            if (photoValidation != null) {
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Photo utilisateur: " + photoValidation);
                errorResult.setRequestId(requestId);

                // Sauvegarder l'erreur
                resultService.saveErrorResult(requestId, userIdentifier, "Validation photo: " + photoValidation, null);

                return errorResult;
            }

            // CRÉE UNE SESSION TEMPORAIRE EN BASE DE DONNÉES
            sessionId = sessionService.createTemporarySession(userIdentifier);
            System.out.println(" Session temporaire créée en base: " + sessionId);

            // SAUVEGARDER LES FICHIERS (OpenKM + PostgreSQL)
            System.out.println(" Début sauvegarde document...");
            FileStorageService.FileStorageResult docResult = fileStorageService.saveIdentityDocument(identityDocument, sessionId);
            if (!docResult.isSuccess()) {
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Erreur sauvegarde document: " + docResult.getError());
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, userIdentifier, "Sauvegarde document: " + docResult.getError(), sessionId);
                return errorResult;
            }
            documentFileId = docResult.getFileId();

            System.out.println(" Début sauvegarde photo...");
            FileStorageService.FileStorageResult photoResult = fileStorageService.saveUserPhoto(userPhoto, sessionId);
            if (!photoResult.isSuccess()) {
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Erreur sauvegarde photo: " + photoResult.getError());
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, userIdentifier, "Sauvegarde photo: " + photoResult.getError(), sessionId);
                return errorResult;
            }
            photoFileId = photoResult.getFileId();

            System.out.println(" Fichiers sauvegardés - Doc: " + documentFileId + ", Photo: " + photoFileId);

            // EXTRACTION DES DONNÉES DU DOCUMENT
            System.out.println("Début extraction document...");
            DocumentExtractionService.DocumentExtractionResult documentExtraction = null;
            try {
                // Récupérer le chemin pour traitement (peut être temporaire depuis OpenKM)
                String docPath = fileStorageService.getFilePathForProcessing(sessionId, FileType.IDENTITY_DOCUMENT);
                documentExtraction = documentExtractionService.extractDocumentData(docPath);

                System.out.println(" Extraction document: " + (documentExtraction.isSuccessful() ? "SUCCÈS" : "ÉCHEC"));
            } catch (Exception e) {
                System.out.println(" Erreur extraction document: " + e.getMessage());
                // On continue même si l'extraction échoue
            }

            // COMPARAISON FACIALE
            System.out.println(" Début comparaison faciale...");
            try {
                // Récupérer les chemins pour traitement
                String docPath = fileStorageService.getFilePathForProcessing(sessionId, FileType.IDENTITY_DOCUMENT);
                String photoPath = fileStorageService.getFilePathForProcessing(sessionId, FileType.USER_PHOTO);

                FaceComparisonService.FaceComparisonResult faceComparison =
                        faceComparisonService.compareImages(docPath, photoPath);

                System.out.println(" Comparaison faciale terminée - Confiance: " + faceComparison.getConfidence());

                // METTRE À JOUR LA SESSION AVEC LES DONNÉES D'EXTRACTION
                if (documentExtraction != null && documentExtraction.isSuccessful()) {
                    sessionService.updateSessionWithExtractionData(
                            sessionId,
                            documentExtraction.getDocumentType(),
                            documentExtraction.getIssuingCountry(),
                            documentExtraction.getExtractedData()
                    );
                }

                //GÉNÉRER LE RÉSULTAT ET SAUVEGARDER
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto result;

                if (documentExtraction != null && documentExtraction.isSuccessful()) {
                    result = VerificationResultDto.successWithData(
                            requestId,
                            userIdentifier,
                            faceComparison.getConfidence(),
                            faceComparison.isVerified(),
                            documentExtraction.getExtractedData(),
                            documentExtraction.getDocumentType(),
                            documentExtraction.getIssuingCountry()
                    );
                } else {
                    result = VerificationResultDto.success(
                            requestId,
                            userIdentifier,
                            faceComparison.getConfidence(),
                            faceComparison.isVerified()
                    );

                    if (documentExtraction != null && !documentExtraction.isSuccessful()) {
                        result.setMessage("Comparaison faciale réussie. Extraction document échouée: " +
                                documentExtraction.getError());
                    }
                }

                // Sauvegarder le résultat
                resultService.saveVerificationResult(result, sessionId, documentFileId, photoFileId);

                // Marquer la session comme terminée
                sessionService.markSessionAsCompleted(sessionId);

                System.out.println(" Vérification terminée - Request ID: " + requestId);
                return result;

            } catch (Exception e) {
                System.out.println(" Erreur comparaison faciale: " + e.getMessage());
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Erreur comparaison faciale: " + e.getMessage());
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, userIdentifier, "Comparaison faciale: " + e.getMessage(), sessionId);
                return errorResult;
            }

        } catch (Exception e) {
            System.out.println(" Erreur globale: " + e.getMessage());
            String requestId = sessionService.generateShortRequestId();
            VerificationResultDto errorResult = VerificationResultDto.error("Erreur lors de la vérification: " + e.getMessage());
            errorResult.setRequestId(requestId);

            resultService.saveErrorResult(requestId, userIdentifier, "Erreur globale: " + e.getMessage(), sessionId);
            return errorResult;
        } finally {
            // Nettoyage des fichiers temporaires après traitement
            cleanupTempFiles();
        }
    }

    /**
     * Traitement document seul
     */
    @Transactional
    public DocumentProcessingResult processDocumentOnly(String userIdentifier, CompletedFileUpload identityDocument) {
        String sessionId = null;
        UUID documentFileId = null;

        try {
            // Validation
            String docValidation = fileValidator.getValidationError(identityDocument);
            if (docValidation != null) {
                return DocumentProcessingResult.error("Document d'identité: " + docValidation);
            }

            // Créer session temporaire EN BASE
            sessionId = sessionService.createTemporarySession(userIdentifier);
            System.out.println(" Session temporaire créée: " + sessionId);

            //  Sauvegarder le document
            FileStorageService.FileStorageResult docResult = fileStorageService.saveIdentityDocument(identityDocument, sessionId);
            if (!docResult.isSuccess()) {
                return DocumentProcessingResult.error("Erreur sauvegarde: " + docResult.getError());
            }
            documentFileId = docResult.getFileId();

            //  Extraction
            String docPath = fileStorageService.getFilePathForProcessing(sessionId, FileType.IDENTITY_DOCUMENT);
            DocumentExtractionService.DocumentExtractionResult extraction =
                    documentExtractionService.extractDocumentData(docPath);

            if (!extraction.isSuccessful()) {
                return DocumentProcessingResult.error("Échec extraction: " + extraction.getError());
            }

            //  Mettre à jour la session avec les données d'extraction
            sessionService.updateSessionWithExtractionData(
                    sessionId,
                    extraction.getDocumentType(),
                    extraction.getIssuingCountry(),
                    extraction.getExtractedData()
            );

            return DocumentProcessingResult.success(
                    sessionId,
                    extraction.getDocumentType(),
                    extraction.getIssuingCountry(),
                    extraction.getExtractedData()
            );

        } catch (Exception e) {
            return DocumentProcessingResult.error("Erreur traitement document: " + e.getMessage());
        }
    }

    /**
     * Comparaison avec photo
     */
    @Transactional
    public VerificationResultDto processPhotoComparison(String documentId, CompletedFileUpload userPhoto) {
        UUID photoFileId = null;

        try {
            // Récupérer la session
            VerificationSession session = sessionService.getSession(documentId);
            if (session == null) {
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Session non trouvée ou expirée: " + documentId);
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, "unknown", "Session non trouvée: " + documentId, documentId);
                return errorResult;
            }

            // Validation photo
            String photoValidation = fileValidator.getValidationError(userPhoto);
            if (photoValidation != null) {
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Photo utilisateur: " + photoValidation);
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, session.getUserIdentifier(), "Validation photo: " + photoValidation, documentId);
                return errorResult;
            }

            // Sauvegarder la photo
            FileStorageService.FileStorageResult photoResult = fileStorageService.saveUserPhoto(userPhoto, documentId);
            if (!photoResult.isSuccess()) {
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Erreur sauvegarde photo: " + photoResult.getError());
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, session.getUserIdentifier(), "Sauvegarde photo: " + photoResult.getError(), documentId);
                return errorResult;
            }
            photoFileId = photoResult.getFileId();

            // Comparaison faciale
            String docPath = fileStorageService.getFilePathForProcessing(documentId, FileType.IDENTITY_DOCUMENT);
            String photoPath = fileStorageService.getFilePathForProcessing(documentId, FileType.USER_PHOTO);

            FaceComparisonService.FaceComparisonResult faceComparison =
                    faceComparisonService.compareImages(docPath, photoPath);

            // Créer et sauvegarder le résultat
            String requestId = sessionService.generateShortRequestId();
            VerificationResultDto result = VerificationResultDto.successWithData(
                    requestId,
                    session.getUserIdentifier(),
                    faceComparison.getConfidence(),
                    faceComparison.isVerified(),
                    session.getExtractedData(),
                    session.getDocumentType(),
                    session.getIssuingCountry()
            );

            //Récupérer l'ID du fichier document correctement
            VerificationFile documentFile = fileStorageService.getFileMetadata(documentId, FileType.IDENTITY_DOCUMENT);
            UUID documentFileId = documentFile != null ? documentFile.getId() : null;

            resultService.saveVerificationResult(result, documentId, documentFileId, photoFileId);

            // Marquer la session comme terminée
            sessionService.markSessionAsCompleted(documentId);

            return result;

        } catch (Exception e) {
            System.err.println(" Erreur dans processPhotoComparison: " + e.getMessage());
            e.printStackTrace();

            String requestId = sessionService.generateShortRequestId();
            VerificationResultDto errorResult = VerificationResultDto.error("Erreur comparaison: " + e.getMessage());
            errorResult.setRequestId(requestId);

            resultService.saveErrorResult(requestId, "unknown", "Erreur comparaison: " + e.getMessage(), documentId);
            return errorResult;
        } finally {
            cleanupTempFiles();
        }
    }

    // MÉTHODES UTILITAIRES

    private void cleanupTempFiles() {
        try {
            fileStorageService.cleanupExpiredTempFiles();
        } catch (Exception e) {
            System.err.println("Erreur nettoyage fichiers temporaires: " + e.getMessage());
        }
    }

    // CLASSE DE RÉSULTAT POUR TRAITEMENT DOCUMENT
    public static class DocumentProcessingResult {
        private final boolean success;
        private final String sessionId;
        private final String documentType;
        private final String issuingCountry;
        private final java.util.Map<String, Object> extractedData;
        private final String error;

        private DocumentProcessingResult(boolean success, String sessionId, String documentType,
                                         String issuingCountry, java.util.Map<String, Object> extractedData, String error) {
            this.success = success;
            this.sessionId = sessionId;
            this.documentType = documentType;
            this.issuingCountry = issuingCountry;
            this.extractedData = extractedData;
            this.error = error;
        }

        public static DocumentProcessingResult success(String sessionId, String documentType,
                                                       String issuingCountry, java.util.Map<String, Object> extractedData) {
            return new DocumentProcessingResult(true, sessionId, documentType, issuingCountry, extractedData, null);
        }

        public static DocumentProcessingResult error(String error) {
            return new DocumentProcessingResult(false, null, null, null, null, error);
        }

        // Getters
        public boolean isSuccess() { return success; }
        public String getSessionId() { return sessionId; }
        public String getDocumentType() { return documentType; }
        public String getIssuingCountry() { return issuingCountry; }
        public java.util.Map<String, Object> getExtractedData() { return extractedData; }
        public String getError() { return error; }
    }
}