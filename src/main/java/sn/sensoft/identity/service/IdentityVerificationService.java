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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@Singleton
public class IdentityVerificationService {

    private static final Logger log = LoggerFactory.getLogger(IdentityVerificationService.class);

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

    public IdentityVerificationService() {
        log.info("IdentityVerificationService initialisé");
    }

    @Transactional
    public VerificationResultDto processVerification(String userIdentifier,
                                                     CompletedFileUpload identityDocument,
                                                     CompletedFileUpload userPhoto) {
        String sessionId = null;
        UUID documentFileId = null;
        UUID photoFileId = null;

        log.info("Début vérification complète pour utilisateur: {}", userIdentifier);

        try {
            // VALIDATION DES FICHIERS
            log.debug("Validation des fichiers pour utilisateur: {}", userIdentifier);

            String docValidation = fileValidator.getValidationError(identityDocument);
            if (docValidation != null) {
                log.warn("Validation document échouée pour utilisateur {}: {}", userIdentifier, docValidation);
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Document d'identité: " + docValidation);
                errorResult.setRequestId(requestId);

                // Sauvegarder l'erreur
                resultService.saveErrorResult(requestId, userIdentifier, "Validation document: " + docValidation, null);

                return errorResult;
            }

            String photoValidation = fileValidator.getValidationError(userPhoto);
            if (photoValidation != null) {
                log.warn("Validation photo échouée pour utilisateur {}: {}", userIdentifier, photoValidation);
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Photo utilisateur: " + photoValidation);
                errorResult.setRequestId(requestId);

                // Sauvegarder l'erreur
                resultService.saveErrorResult(requestId, userIdentifier, "Validation photo: " + photoValidation, null);

                return errorResult;
            }

            log.debug("Validation des fichiers réussie pour utilisateur: {}", userIdentifier);

            // CRÉE UNE SESSION TEMPORAIRE EN BASE DE DONNÉES
            sessionId = sessionService.createTemporarySession(userIdentifier);
            log.info("Session temporaire créée en base: {} pour utilisateur: {}", sessionId, userIdentifier);

            // SAUVEGARDER LES FICHIERS (OpenKM + PostgreSQL)
            log.debug("Début sauvegarde document pour session: {}", sessionId);
            FileStorageService.FileStorageResult docResult = fileStorageService.saveIdentityDocument(identityDocument, sessionId);
            if (!docResult.isSuccess()) {
                log.error("Erreur sauvegarde document pour session {}: {}", sessionId, docResult.getError());
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Erreur sauvegarde document: " + docResult.getError());
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, userIdentifier, "Sauvegarde document: " + docResult.getError(), sessionId);
                return errorResult;
            }
            documentFileId = docResult.getFileId();

            log.debug("Début sauvegarde photo pour session: {}", sessionId);
            FileStorageService.FileStorageResult photoResult = fileStorageService.saveUserPhoto(userPhoto, sessionId);
            if (!photoResult.isSuccess()) {
                log.error("Erreur sauvegarde photo pour session {}: {}", sessionId, photoResult.getError());
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Erreur sauvegarde photo: " + photoResult.getError());
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, userIdentifier, "Sauvegarde photo: " + photoResult.getError(), sessionId);
                return errorResult;
            }
            photoFileId = photoResult.getFileId();

            log.info("Fichiers sauvegardés avec succès - Session: {}, Doc: {}, Photo: {}", sessionId, documentFileId, photoFileId);

            // EXTRACTION DES DONNÉES DU DOCUMENT
            log.debug("Début extraction document pour session: {}", sessionId);
            DocumentExtractionService.DocumentExtractionResult documentExtraction = null;
            try {
                // Récupérer le chemin pour traitement (peut être temporaire depuis OpenKM)
                String docPath = fileStorageService.getFilePathForProcessing(sessionId, FileType.IDENTITY_DOCUMENT);
                documentExtraction = documentExtractionService.extractDocumentData(docPath);

                if (documentExtraction.isSuccessful()) {
                    log.info("Extraction document réussie pour session: {} - Type: {}, Pays: {}",
                            sessionId, documentExtraction.getDocumentType(), documentExtraction.getIssuingCountry());
                } else {
                    log.warn("Extraction document échouée pour session: {} - Erreur: {}", sessionId, documentExtraction.getError());
                }
            } catch (Exception e) {
                log.error("Erreur extraction document pour session {}: {}", sessionId, e.getMessage(), e);
                // On continue même si l'extraction échoue
            }

            // COMPARAISON FACIALE
            log.debug("Début comparaison faciale pour session: {}", sessionId);
            try {
                // Récupérer les chemins pour traitement
                String docPath = fileStorageService.getFilePathForProcessing(sessionId, FileType.IDENTITY_DOCUMENT);
                String photoPath = fileStorageService.getFilePathForProcessing(sessionId, FileType.USER_PHOTO);

                FaceComparisonService.FaceComparisonResult faceComparison =
                        faceComparisonService.compareImages(docPath, photoPath);

                log.info("Comparaison faciale terminée pour session: {} - Vérifié: {}, Confiance: {:.3f}",
                        sessionId, faceComparison.isVerified(), faceComparison.getConfidence());

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

                log.info("Vérification complète terminée avec succès - Session: {}, RequestId: {}, Match: {}",
                        sessionId, requestId, faceComparison.isVerified());
                return result;

            } catch (Exception e) {
                log.error("Erreur comparaison faciale pour session {}: {}", sessionId, e.getMessage(), e);
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Erreur comparaison faciale: " + e.getMessage());
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, userIdentifier, "Comparaison faciale: " + e.getMessage(), sessionId);
                return errorResult;
            }

        } catch (Exception e) {
            log.error("Erreur globale vérification pour utilisateur {}: {}", userIdentifier, e.getMessage(), e);
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

        log.info("Début traitement document seul pour utilisateur: {}", userIdentifier);

        try {
            // Validation
            String docValidation = fileValidator.getValidationError(identityDocument);
            if (docValidation != null) {
                log.warn("Validation document échouée pour utilisateur {}: {}", userIdentifier, docValidation);
                return DocumentProcessingResult.error("Document d'identité: " + docValidation);
            }

            // Créer session temporaire EN BASE
            sessionId = sessionService.createTemporarySession(userIdentifier);
            log.info("Session temporaire créée: {} pour utilisateur: {}", sessionId, userIdentifier);

            //  Sauvegarder le document
            log.debug("Sauvegarde document pour session: {}", sessionId);
            FileStorageService.FileStorageResult docResult = fileStorageService.saveIdentityDocument(identityDocument, sessionId);
            if (!docResult.isSuccess()) {
                log.error("Erreur sauvegarde document pour session {}: {}", sessionId, docResult.getError());
                return DocumentProcessingResult.error("Erreur sauvegarde: " + docResult.getError());
            }
            documentFileId = docResult.getFileId();

            //  Extraction
            log.debug("Début extraction document pour session: {}", sessionId);
            String docPath = fileStorageService.getFilePathForProcessing(sessionId, FileType.IDENTITY_DOCUMENT);
            DocumentExtractionService.DocumentExtractionResult extraction =
                    documentExtractionService.extractDocumentData(docPath);

            if (!extraction.isSuccessful()) {
                log.error("Échec extraction document pour session {}: {}", sessionId, extraction.getError());
                return DocumentProcessingResult.error("Échec extraction: " + extraction.getError());
            }

            log.info("Extraction document réussie pour session: {} - Type: {}, Pays: {}",
                    sessionId, extraction.getDocumentType(), extraction.getIssuingCountry());

            //  Mettre à jour la session avec les données d'extraction
            sessionService.updateSessionWithExtractionData(
                    sessionId,
                    extraction.getDocumentType(),
                    extraction.getIssuingCountry(),
                    extraction.getExtractedData()
            );

            log.info("Traitement document seul terminé avec succès - Session: {}", sessionId);

            return DocumentProcessingResult.success(
                    sessionId,
                    extraction.getDocumentType(),
                    extraction.getIssuingCountry(),
                    extraction.getExtractedData()
            );

        } catch (Exception e) {
            log.error("Erreur traitement document pour utilisateur {}: {}", userIdentifier, e.getMessage(), e);
            return DocumentProcessingResult.error("Erreur traitement document: " + e.getMessage());
        }
    }

    /**
     * Comparaison avec photo
     */
    @Transactional
    public VerificationResultDto processPhotoComparison(String documentId, CompletedFileUpload userPhoto) {
        UUID photoFileId = null;

        log.info("Début comparaison photo pour document: {}", documentId);

        try {
            // Récupérer la session
            VerificationSession session = sessionService.getSession(documentId);
            if (session == null) {
                log.warn("Session non trouvée ou expirée: {}", documentId);
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Session non trouvée ou expirée: " + documentId);
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, "unknown", "Session non trouvée: " + documentId, documentId);
                return errorResult;
            }

            log.debug("Session récupérée: {} pour utilisateur: {}", documentId, session.getUserIdentifier());

            // Validation photo
            String photoValidation = fileValidator.getValidationError(userPhoto);
            if (photoValidation != null) {
                log.warn("Validation photo échouée pour document {}: {}", documentId, photoValidation);
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Photo utilisateur: " + photoValidation);
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, session.getUserIdentifier(), "Validation photo: " + photoValidation, documentId);
                return errorResult;
            }

            // Sauvegarder la photo
            log.debug("Sauvegarde photo pour document: {}", documentId);
            FileStorageService.FileStorageResult photoResult = fileStorageService.saveUserPhoto(userPhoto, documentId);
            if (!photoResult.isSuccess()) {
                log.error("Erreur sauvegarde photo pour document {}: {}", documentId, photoResult.getError());
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Erreur sauvegarde photo: " + photoResult.getError());
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, session.getUserIdentifier(), "Sauvegarde photo: " + photoResult.getError(), documentId);
                return errorResult;
            }
            photoFileId = photoResult.getFileId();

            log.debug("Photo sauvegardée avec succès: {} pour document: {}", photoFileId, documentId);

            // Comparaison faciale
            log.debug("Début comparaison faciale pour document: {}", documentId);
            String docPath = fileStorageService.getFilePathForProcessing(documentId, FileType.IDENTITY_DOCUMENT);
            String photoPath = fileStorageService.getFilePathForProcessing(documentId, FileType.USER_PHOTO);

            FaceComparisonService.FaceComparisonResult faceComparison =
                    faceComparisonService.compareImages(docPath, photoPath);

            log.info("Comparaison faciale terminée pour document: {} - Vérifié: {}, Confiance: {:.3f}",
                    documentId, faceComparison.isVerified(), faceComparison.getConfidence());

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

            log.info("Comparaison photo terminée avec succès - Document: {}, RequestId: {}, Match: {}",
                    documentId, requestId, faceComparison.isVerified());

            return result;

        } catch (Exception e) {
            log.error("Erreur dans processPhotoComparison pour document {}: {}", documentId, e.getMessage(), e);

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
            log.debug("Nettoyage fichiers temporaires");
            fileStorageService.cleanupExpiredTempFiles();
        } catch (Exception e) {
            log.error("Erreur nettoyage fichiers temporaires: {}", e.getMessage(), e);
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