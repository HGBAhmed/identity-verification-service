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
import sn.sensoft.identity.repository.VerificationFileRepository;
import java.util.Optional;
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

    @Inject
    private VerificationFileRepository fileRepository;

    public IdentityVerificationService() {
        log.info("IdentityVerificationService initialisé");
    }

    //  Méthode +veirfication de type

    /**
     * Traitement document avec validation du type attendu
     */
    @Transactional
    public DocumentProcessingResult processDocumentWithTypeValidation(String userIdentifier,
                                                                      CompletedFileUpload document,
                                                                      String expectedType) {
        log.info("Début traitement document avec validation type pour utilisateur: {}, type attendu: {}",
                userIdentifier, expectedType);

        try {
            // Traitement normal
            DocumentProcessingResult result = processDocumentOnly(userIdentifier, document);

            if (result.isSuccess()) {
                // Vérifier que le type détecté correspond au type attendu
                String detectedType = determineDocumentType(result.getDocumentType());

                if (!isTypeCompatible(expectedType, detectedType)) {
                    log.warn("Type de document incorrect pour utilisateur {}: attendu {}, détecté {}",
                            userIdentifier, expectedType, detectedType);
                    return DocumentProcessingResult.error(
                            "Type de document incorrect. Attendu: " + expectedType +
                                    ", Détecté: " + detectedType
                    );
                }

                log.info("Validation de type réussie pour utilisateur {}: {} confirmé",
                        userIdentifier, expectedType);
            }

            return result;

        } catch (Exception e) {
            log.error("Erreur traitement document avec validation type pour utilisateur {}: {}",
                    userIdentifier, e.getMessage(), e);
            return DocumentProcessingResult.error("Erreur traitement: " + e.getMessage());
        }
    }

    /**
     * Vérification complète avec validation du type attendu et seuil configurable
     */
    @Transactional
    public VerificationResultDto processVerificationWithTypeValidation(String userIdentifier,
                                                                       CompletedFileUpload document,
                                                                       CompletedFileUpload photo,
                                                                       String expectedType,
                                                                       Double threshold) {
        log.info("Début vérification complète avec validation type pour utilisateur: {}, type attendu: {}, seuil: {}",
                userIdentifier, expectedType, threshold);

        try {
            // Traitement normal
            VerificationResultDto result = processVerification(userIdentifier, document, photo, threshold);

            if (!"FAILED".equals(result.getStatus())) {
                // Vérifier le type si on a des données de document
                if (result.getDocumentData() != null && result.getDocumentData().getDocumentType() != null) {
                    String detectedType = determineDocumentType(result.getDocumentData().getDocumentType());

                    if (!isTypeCompatible(expectedType, detectedType)) {
                        log.warn("Type de document incorrect lors de la vérification complète pour utilisateur {}: attendu {}, détecté {}",
                                userIdentifier, expectedType, detectedType);

                        String requestId = sessionService.generateShortRequestId();
                        VerificationResultDto errorResult = VerificationResultDto.error(
                                "Type de document incorrect. Attendu: " + expectedType +
                                        ", Détecté: " + detectedType
                        );
                        errorResult.setRequestId(requestId);

                        resultService.saveErrorResult(requestId, userIdentifier,
                                "Type incorrect: " + expectedType + " vs " + detectedType, null);
                        return errorResult;
                    }

                    log.info("Validation de type réussie lors de la vérification complète pour utilisateur {}: {} confirmé",
                            userIdentifier, expectedType);
                }
            }

            return result;

        } catch (Exception e) {
            log.error("Erreur vérification complète avec validation type pour utilisateur {}: {}",
                    userIdentifier, e.getMessage(), e);

            String requestId = sessionService.generateShortRequestId();
            VerificationResultDto errorResult = VerificationResultDto.error("Erreur traitement: " + e.getMessage());
            errorResult.setRequestId(requestId);

            resultService.saveErrorResult(requestId, userIdentifier, "Erreur: " + e.getMessage(), null);
            return errorResult;
        }
    }

    /**
     * Vérification complète avec validation du type attendu (sans seuil - compatibilité)
     */
    @Transactional
    public VerificationResultDto processVerificationWithTypeValidation(String userIdentifier,
                                                                       CompletedFileUpload document,
                                                                       CompletedFileUpload photo,
                                                                       String expectedType) {
        return processVerificationWithTypeValidation(userIdentifier, document, photo, expectedType, null);
    }

    /**
     * Traitement recto/verso pour cartes d'identité
     */
    @Transactional
    public DocumentProcessingResult processRectoVersoDocument(String userIdentifier,
                                                              CompletedFileUpload rectoDocument,
                                                              CompletedFileUpload versoDocument) {
        String sessionId = null;
        UUID rectoFileId = null;
        UUID versoFileId = null;

        log.info("Début traitement recto/verso pour utilisateur: {}", userIdentifier);

        try {
            // Validation des fichiers
            String rectoValidation = fileValidator.getValidationError(rectoDocument);
            if (rectoValidation != null) {
                log.warn("Validation recto échouée pour utilisateur {}: {}", userIdentifier, rectoValidation);
                return DocumentProcessingResult.error("Fichier recto: " + rectoValidation);
            }

            String versoValidation = fileValidator.getValidationError(versoDocument);
            if (versoValidation != null) {
                log.warn("Validation verso échouée pour utilisateur {}: {}", userIdentifier, versoValidation);
                return DocumentProcessingResult.error("Fichier verso: " + versoValidation);
            }

            // Créer session temporaire
            sessionId = sessionService.createTemporarySession(userIdentifier);
            log.info("Session temporaire créée: {} pour utilisateur: {}", sessionId, userIdentifier);

            // Sauvegarder les fichiers
            log.debug("Sauvegarde fichier recto pour session: {}", sessionId);
            FileStorageService.FileStorageResult rectoResult = fileStorageService.saveIdentityDocument(rectoDocument, sessionId);
            if (!rectoResult.isSuccess()) {
                log.error("Erreur sauvegarde recto pour session {}: {}", sessionId, rectoResult.getError());
                return DocumentProcessingResult.error("Erreur sauvegarde recto: " + rectoResult.getError());
            }
            rectoFileId = rectoResult.getFileId();

            log.debug("Sauvegarde fichier verso pour session: {}", sessionId);
            FileStorageService.FileStorageResult versoResult = fileStorageService.saveIdentityDocument(versoDocument, sessionId);
            if (!versoResult.isSuccess()) {
                log.error("Erreur sauvegarde verso pour session {}: {}", sessionId, versoResult.getError());
                return DocumentProcessingResult.error("Erreur sauvegarde verso: " + versoResult.getError());
            }
            versoFileId = versoResult.getFileId();

            log.info("Fichiers recto/verso sauvegardés avec succès - Session: {}, Recto: {}, Verso: {}",
                    sessionId, rectoFileId, versoFileId);

            // Extraction des données avec les deux fichiers
            log.debug("Début extraction recto/verso pour session: {}", sessionId);
            DocumentExtractionService.DocumentExtractionResult extraction = null;

            try {
                // Récupérer les chemins pour traitement
                String rectoPath = fileStorageService.getFilePathForProcessing(sessionId, FileType.IDENTITY_DOCUMENT);
                String versoPath = fileStorageService.getFilePathForProcessing(sessionId, FileType.IDENTITY_DOCUMENT);

                // Utiliser la nouvelle méthode d'extraction recto/verso
                extraction = documentExtractionService.extractRectoVersoData(rectoPath, versoPath);

                if (extraction.isSuccessful()) {
                    log.info("Extraction recto/verso réussie pour session: {} - Type: {}, Pays: {}",
                            sessionId, extraction.getDocumentType(), extraction.getIssuingCountry());
                } else {
                    log.warn("Extraction recto/verso échouée pour session: {} - Erreur: {}",
                            sessionId, extraction.getError());
                    return DocumentProcessingResult.error("Échec extraction recto/verso: " + extraction.getError());
                }
            } catch (Exception e) {
                log.error("Erreur extraction recto/verso pour session {}: {}", sessionId, e.getMessage(), e);
                return DocumentProcessingResult.error("Erreur extraction recto/verso: " + e.getMessage());
            }

            // Mettre à jour la session avec les données d'extraction
            sessionService.updateSessionWithExtractionData(
                    sessionId,
                    extraction.getDocumentType(),
                    extraction.getIssuingCountry(),
                    extraction.getExtractedData()
            );

            log.info("Traitement recto/verso terminé avec succès - Session: {}", sessionId);

            return DocumentProcessingResult.success(
                    sessionId,
                    extraction.getDocumentType(),
                    extraction.getIssuingCountry(),
                    extraction.getExtractedData()
            );

        } catch (Exception e) {
            log.error("Erreur traitement recto/verso pour utilisateur {}: {}", userIdentifier, e.getMessage(), e);
            return DocumentProcessingResult.error("Erreur traitement recto/verso: " + e.getMessage());
        }
    }

    // MÉTHODES UTILITAIRES

    /**
     * Détermine le type générique à partir du type détecté
     */
    private String determineDocumentType(String extractedType) {
        if (extractedType == null) {
            return "UNKNOWN";
        }

        if (extractedType.equals("PASSPORT")) {
            return "PASSPORT";
        } else if (extractedType.startsWith("ID_CARD")) {
            return "ID_CARD";
        }

        return "UNKNOWN";
    }

    /**
     * Vérifie la compatibilité entre le type attendu et le type détecté
     */
    private boolean isTypeCompatible(String expected, String detected) {
        if (expected == null || detected == null) {
            return true; // Pas de validation si types non spécifiés
        }

        // Correspondance exacte
        if (expected.equals(detected)) {
            return true;
        }

        // ID_CARD générique accepte tous les types de cartes d'identité
        if (expected.equals("ID_CARD") && detected.equals("ID_CARD")) {
            return true;
        }

        return false;
    }

    /**
     * Vérification complète avec seuil configurable
     */
    @Transactional
    public VerificationResultDto processVerification(String userIdentifier,
                                                     CompletedFileUpload identityDocument,
                                                     CompletedFileUpload userPhoto,
                                                     Double threshold) {
        String sessionId = null;
        UUID documentFileId = null;
        UUID photoFileId = null;

        log.info("Début vérification complète pour utilisateur: {} avec seuil: {}", userIdentifier, threshold);

        try {
            // VALIDATION DES FICHIERS
            log.debug("Validation des fichiers pour utilisateur: {}", userIdentifier);

            String docValidation = fileValidator.getValidationError(identityDocument);
            if (docValidation != null) {
                log.warn("Validation document échouée pour utilisateur {}: {}", userIdentifier, docValidation);
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Document d'identité: " + docValidation);
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, userIdentifier, "Validation document: " + docValidation, null);
                return errorResult;
            }

            String photoValidation = fileValidator.getValidationError(userPhoto);
            if (photoValidation != null) {
                log.warn("Validation photo échouée pour utilisateur {}: {}", userIdentifier, photoValidation);
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Photo utilisateur: " + photoValidation);
                errorResult.setRequestId(requestId);

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
            }

            // COMPARAISON FACIALE AVEC SEUIL
            log.debug("Début comparaison faciale pour session: {} avec seuil: {}", sessionId, threshold);
            try {
                String docPath = fileStorageService.getFilePathForProcessing(sessionId, FileType.IDENTITY_DOCUMENT);
                String photoPath = fileStorageService.getFilePathForProcessing(sessionId, FileType.USER_PHOTO);

                FaceComparisonService.FaceComparisonResult faceComparison =
                        faceComparisonService.compareImages(docPath, photoPath, threshold);

                log.info("Comparaison faciale terminée pour session: {} - Vérifié: {}, Confiance: {:.3f}, Seuil: {}",
                        sessionId, faceComparison.isVerified(), faceComparison.getConfidence(),
                        threshold != null ? threshold : "défaut");

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

                resultService.saveVerificationResult(result, sessionId, documentFileId, photoFileId);
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
            cleanupTempFiles();
        }
    }

    /**
     * Vérification complète
     */
    @Transactional
    public VerificationResultDto processVerification(String userIdentifier,
                                                     CompletedFileUpload identityDocument,
                                                     CompletedFileUpload userPhoto) {
        return processVerification(userIdentifier, identityDocument, userPhoto, null);
    }

    @Transactional
    public DocumentProcessingResult processDocumentOnly(String userIdentifier, CompletedFileUpload identityDocument) {
        String sessionId = null;
        UUID documentFileId = null;

        log.info("Début traitement document seul pour utilisateur: {}", userIdentifier);

        try {
            String docValidation = fileValidator.getValidationError(identityDocument);
            if (docValidation != null) {
                log.warn("Validation document échouée pour utilisateur {}: {}", userIdentifier, docValidation);
                return DocumentProcessingResult.error("Document d'identité: " + docValidation);
            }

            sessionId = sessionService.createTemporarySession(userIdentifier);
            log.info("Session temporaire créée: {} pour utilisateur: {}", sessionId, userIdentifier);

            log.debug("Sauvegarde document pour session: {}", sessionId);
            FileStorageService.FileStorageResult docResult = fileStorageService.saveIdentityDocument(identityDocument, sessionId);
            if (!docResult.isSuccess()) {
                log.error("Erreur sauvegarde document pour session {}: {}", sessionId, docResult.getError());
                return DocumentProcessingResult.error("Erreur sauvegarde: " + docResult.getError());
            }
            documentFileId = docResult.getFileId();

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
     * Comparaison photo avec seuil configurable
     */
    @Transactional
    public VerificationResultDto processPhotoComparison(String documentId, CompletedFileUpload userPhoto, Double threshold) {
        UUID photoFileId = null;

        log.info("Début comparaison photo pour document: {} avec seuil: {}", documentId, threshold);

        try {
            VerificationSession session = sessionService.getSession(documentId);
            if (session == null) {
                log.warn("Session non trouvée ou expirée: {}", documentId);
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Session non trouvée ou expirée: " + documentId);
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, "unknown", "Session non trouvée: " + documentId, documentId);
                return errorResult;
            }

            Optional<VerificationFile> existingPhoto = fileRepository.findBySessionIdAndFileType(documentId, FileType.USER_PHOTO);
            if (existingPhoto.isPresent()) {
                log.debug("Suppression ancienne photo pour session: {}", documentId);
                fileStorageService.permanentDeleteFile(existingPhoto.get().getId());
            }
            log.debug("Session récupérée: {} pour utilisateur: {}", documentId, session.getUserIdentifier());

            String photoValidation = fileValidator.getValidationError(userPhoto);
            if (photoValidation != null) {
                log.warn("Validation photo échouée pour document {}: {}", documentId, photoValidation);
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Photo utilisateur: " + photoValidation);
                errorResult.setRequestId(requestId);

                resultService.saveErrorResult(requestId, session.getUserIdentifier(), "Validation photo: " + photoValidation, documentId);
                return errorResult;
            }

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

            log.debug("Début comparaison faciale pour document: {} avec seuil: {}", documentId, threshold);
            String docPath = fileStorageService.getFilePathForProcessing(documentId, FileType.IDENTITY_DOCUMENT);
            String photoPath = fileStorageService.getFilePathForProcessing(documentId, FileType.USER_PHOTO);

            FaceComparisonService.FaceComparisonResult faceComparison =
                    faceComparisonService.compareImages(docPath, photoPath, threshold);

            log.info("Comparaison faciale terminée pour document: {} - Vérifié: {}, Confiance: {:.3f}, Seuil: {}",
                    documentId, faceComparison.isVerified(), faceComparison.getConfidence(),
                    threshold != null ? threshold : "défaut");

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

            VerificationFile documentFile = fileStorageService.getFileMetadata(documentId, FileType.IDENTITY_DOCUMENT);
            UUID documentFileId = documentFile != null ? documentFile.getId() : null;

            resultService.saveVerificationResult(result, documentId, documentFileId, photoFileId);
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

    /**
     * Comparaison photo
     */
    @Transactional
    public VerificationResultDto processPhotoComparison(String documentId, CompletedFileUpload userPhoto) {
        return processPhotoComparison(documentId, userPhoto, null);
    }

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

        public boolean isSuccess() { return success; }
        public String getSessionId() { return sessionId; }
        public String getDocumentType() { return documentType; }
        public String getIssuingCountry() { return issuingCountry; }
        public java.util.Map<String, Object> getExtractedData() { return extractedData; }
        public String getError() { return error; }
    }
}