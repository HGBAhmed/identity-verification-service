package sn.sensoft.identity.controller;

import sn.sensoft.identity.dto.DocumentUploadResponse;
import sn.sensoft.identity.dto.VerificationResultDto;
import sn.sensoft.identity.dto.VerificationResultDto.DocumentExtractionData;
import sn.sensoft.identity.service.IdentityVerificationService;
import sn.sensoft.identity.service.VerificationResultService;
import sn.sensoft.identity.service.VerificationSessionService;
import sn.sensoft.identity.util.FileValidator;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;

import io.micronaut.http.server.types.files.StreamedFile;
import sn.sensoft.identity.dto.FileInfoDto;
import sn.sensoft.identity.service.FileManagementService;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller("/api/v1/verification")
@ExecuteOn(TaskExecutors.BLOCKING)
public class IdentityVerificationController {

    private static final Logger log = LoggerFactory.getLogger(IdentityVerificationController.class);

    @Inject
    private IdentityVerificationService verificationService;

    @Inject
    private VerificationSessionService sessionService;

    @Inject
    private VerificationResultService resultService;

    @Inject
    private FileValidator fileValidator;

    @Inject
    private FileManagementService fileManagementService;


    /**
     * Upload et extraction d'un PASSEPORT
     */
    @Post(value = "/passport/document", consumes = MediaType.MULTIPART_FORM_DATA)
    @Secured({"VERIFICATION_USER", "ADMIN"})
    public HttpResponse<DocumentUploadResponse> uploadPassportDocument(
            @Part("passportDocument") CompletedFileUpload passportDocument,
            @Part("userIdentifier") String userIdentifier) {

        log.info("Début upload passeport pour utilisateur: {}", userIdentifier);
        return processDocumentUpload(passportDocument, userIdentifier, "PASSPORT");
    }

    /**
     * Vérification complète PASSEPORT
     */
    @Post(value = "/passport/complete", consumes = MediaType.MULTIPART_FORM_DATA)
    @Secured({"VERIFICATION_USER", "ADMIN"})
    public HttpResponse<VerificationResultDto> verifyPassport(
            @Part("passportDocument") CompletedFileUpload passportDocument,
            @Part("userPhoto") CompletedFileUpload userPhoto,
            @Part("userIdentifier") String userIdentifier) {

        log.info("Début vérification complète passeport pour utilisateur: {}", userIdentifier);
        return processCompleteVerification(passportDocument, userPhoto, userIdentifier, "PASSPORT");
    }

    /**
     * Upload et extraction d'une CARTE D'IDENTITÉ
     */
    @Post(value = "/id-card/document", consumes = MediaType.MULTIPART_FORM_DATA)
    @Secured({"VERIFICATION_USER", "ADMIN"})
    public HttpResponse<DocumentUploadResponse> uploadIdCardDocument(
            @Part("idCardDocument") CompletedFileUpload idCardDocument,
            @Part("userIdentifier") String userIdentifier) {

        log.info("Début upload carte d'identité pour utilisateur: {}", userIdentifier);
        return processDocumentUpload(idCardDocument, userIdentifier, "ID_CARD");
    }

    /**
     * Vérification complète CARTE D'IDENTITÉ
     */
    @Post(value = "/id-card/complete", consumes = MediaType.MULTIPART_FORM_DATA)
    @Secured({"VERIFICATION_USER", "ADMIN"})
    public HttpResponse<VerificationResultDto> verifyIdCard(
            @Part("idCardDocument") CompletedFileUpload idCardDocument,
            @Part("userPhoto") CompletedFileUpload userPhoto,
            @Part("userIdentifier") String userIdentifier) {

        log.info("Début vérification complète carte d'identité pour utilisateur: {}", userIdentifier);
        return processCompleteVerification(idCardDocument, userPhoto, userIdentifier, "ID_CARD");
    }

    /**
     * Upload RECTO + VERSO d'une carte d'identité
     */
    @Post(value = "/id-card/recto-verso", consumes = MediaType.MULTIPART_FORM_DATA)
    @Secured({"VERIFICATION_USER", "ADMIN"})
    public HttpResponse<DocumentUploadResponse> uploadIdCardBothSides(
            @Part("rectoDocument") CompletedFileUpload rectoDocument,
            @Part("versoDocument") CompletedFileUpload versoDocument,
            @Part("userIdentifier") String userIdentifier) {

        try {
            log.info("Début upload recto/verso carte d'identité pour utilisateur: {}", userIdentifier);

            // Validation des deux fichiers
            String rectoValidation = fileValidator.getValidationError(rectoDocument);
            if (rectoValidation != null) {
                log.warn("Validation recto échouée pour utilisateur {}: {}", userIdentifier, rectoValidation);
                return HttpResponse.badRequest(
                        DocumentUploadResponse.error("Fichier recto: " + rectoValidation)
                );
            }

            String versoValidation = fileValidator.getValidationError(versoDocument);
            if (versoValidation != null) {
                log.warn("Validation verso échouée pour utilisateur {}: {}", userIdentifier, versoValidation);
                return HttpResponse.badRequest(
                        DocumentUploadResponse.error("Fichier verso: " + versoValidation)
                );
            }

            // Traitement recto/verso
            IdentityVerificationService.DocumentProcessingResult result =
                    verificationService.processRectoVersoDocument(userIdentifier, rectoDocument, versoDocument);

            if (!result.isSuccess()) {
                log.warn("Échec traitement recto/verso pour utilisateur {}: {}", userIdentifier, result.getError());
                return HttpResponse.badRequest(
                        DocumentUploadResponse.error(result.getError())
                );
            }

            log.info("Recto/verso traité avec succès - Session: {} pour utilisateur: {}",
                    result.getSessionId(), userIdentifier);

            // Préparer la réponse
            DocumentExtractionData responseData = new DocumentExtractionData();
            responseData.setDocumentType(result.getDocumentType());
            responseData.setIssuingCountry(result.getIssuingCountry());
            responseData.setExtractedFields(result.getExtractedData());

            DocumentUploadResponse response = DocumentUploadResponse.successIdCard(
                    result.getSessionId(), responseData, "BOTH");

            return HttpResponse.ok(response);

        } catch (Exception e) {
            log.error("Erreur globale recto/verso pour utilisateur {}: {}", userIdentifier, e.getMessage(), e);
            return HttpResponse.badRequest(
                    DocumentUploadResponse.error("Erreur lors du traitement: " + e.getMessage())
            );
        }
    }

    /**
     * Comparaison avec photo
     */
    @Post(value = "/compare/{documentId}", consumes = MediaType.MULTIPART_FORM_DATA)
    @Secured({"VERIFICATION_USER", "ADMIN"})
    public HttpResponse<VerificationResultDto> compareWithPhoto(
            @PathVariable String documentId,
            @Part("userPhoto") CompletedFileUpload userPhoto) {

        try {
            log.debug("Début comparaison photo pour document: {}", documentId);

            String photoValidation = fileValidator.getValidationError(userPhoto);
            if (photoValidation != null) {
                log.warn("Validation échouée pour photo document {}: {}", documentId, photoValidation);
                return HttpResponse.badRequest(
                        VerificationResultDto.error("Photo utilisateur: " + photoValidation)
                );
            }

            VerificationResultDto result = verificationService.processPhotoComparison(documentId, userPhoto);

            if ("FAILED".equals(result.getStatus())) {
                log.warn("Comparaison échouée pour document: {}", documentId);
                return HttpResponse.badRequest(result);
            }

            log.info("Comparaison terminée avec succès - Request ID: {} pour document: {}",
                    result.getRequestId(), documentId);
            return HttpResponse.ok(result);

        } catch (Exception e) {
            log.error("Erreur globale comparaison pour document {}: {}", documentId, e.getMessage(), e);
            return HttpResponse.badRequest(
                    VerificationResultDto.error("Erreur lors de la comparaison: " + e.getMessage())
            );
        }
    }

    //  MÉTHODES PRIVÉES COMMUNES

    private HttpResponse<DocumentUploadResponse> processDocumentUpload(
            CompletedFileUpload document, String userIdentifier, String expectedType) {

        try {
            log.debug("Début traitement document pour utilisateur: {}, type attendu: {}",
                    userIdentifier, expectedType);

            // Validation du fichier
            String docValidation = fileValidator.getValidationError(document);
            if (docValidation != null) {
                log.warn("Validation échouée pour document utilisateur {}: {}", userIdentifier, docValidation);
                return HttpResponse.badRequest(
                        DocumentUploadResponse.error("Document: " + docValidation)
                );
            }

            // Traitement avec validation de type si spécifié
            IdentityVerificationService.DocumentProcessingResult result;
            if (expectedType != null) {
                result = verificationService.processDocumentWithTypeValidation(userIdentifier, document, expectedType);
            } else {
                result = verificationService.processDocumentOnly(userIdentifier, document);
            }

            if (!result.isSuccess()) {
                log.warn("Échec traitement document pour utilisateur {}: {}", userIdentifier, result.getError());
                return HttpResponse.badRequest(
                        DocumentUploadResponse.error(result.getError())
                );
            }

            log.info("Document traité avec succès - Session: {} pour utilisateur: {}",
                    result.getSessionId(), userIdentifier);

            // Préparer la réponse
            DocumentExtractionData responseData = new DocumentExtractionData();
            responseData.setDocumentType(result.getDocumentType());
            responseData.setIssuingCountry(result.getIssuingCountry());
            responseData.setExtractedFields(result.getExtractedData());

            DocumentUploadResponse response;
            if (expectedType != null && expectedType.equals("PASSPORT")) {
                response = DocumentUploadResponse.successPassport(result.getSessionId(), responseData);
            } else if (expectedType != null && expectedType.equals("ID_CARD")) {
                response = DocumentUploadResponse.successIdCard(result.getSessionId(), responseData, "RECTO");
            } else {
                response = DocumentUploadResponse.success(result.getSessionId(), responseData);
            }

            return HttpResponse.ok(response);

        } catch (Exception e) {
            log.error("Erreur globale traitement document pour utilisateur {}: {}", userIdentifier, e.getMessage(), e);
            return HttpResponse.badRequest(
                    DocumentUploadResponse.error("Erreur lors du traitement: " + e.getMessage())
            );
        }
    }

    private HttpResponse<VerificationResultDto> processCompleteVerification(
            CompletedFileUpload document, CompletedFileUpload photo, String userIdentifier, String expectedType) {

        try {
            log.debug("Début vérification complète pour utilisateur: {}, type attendu: {}",
                    userIdentifier, expectedType);

            // Validation des fichiers
            String docValidation = fileValidator.getValidationError(document);
            if (docValidation != null) {
                log.warn("Validation document échouée pour utilisateur {}: {}", userIdentifier, docValidation);
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Document: " + docValidation);
                errorResult.setRequestId(requestId);
                resultService.saveErrorResult(requestId, userIdentifier, "Validation document: " + docValidation, null);
                return HttpResponse.badRequest(errorResult);
            }

            String photoValidation = fileValidator.getValidationError(photo);
            if (photoValidation != null) {
                log.warn("Validation photo échouée pour utilisateur {}: {}", userIdentifier, photoValidation);
                String requestId = sessionService.generateShortRequestId();
                VerificationResultDto errorResult = VerificationResultDto.error("Photo: " + photoValidation);
                errorResult.setRequestId(requestId);
                resultService.saveErrorResult(requestId, userIdentifier, "Validation photo: " + photoValidation, null);
                return HttpResponse.badRequest(errorResult);
            }

            // Traitement avec validation de type si spécifié
            VerificationResultDto result;
            if (expectedType != null) {
                result = verificationService.processVerificationWithTypeValidation(
                        userIdentifier, document, photo, expectedType);
            } else {
                result = verificationService.processVerification(userIdentifier, document, photo);
            }

            if ("FAILED".equals(result.getStatus())) {
                log.warn("Vérification complète échouée pour utilisateur: {}", userIdentifier);
                return HttpResponse.badRequest(result);
            }

            log.info("Vérification complète réussie pour utilisateur: {} - Request ID: {}",
                    userIdentifier, result.getRequestId());
            return HttpResponse.ok(result);

        } catch (Exception e) {
            log.error("Erreur globale vérification complète pour utilisateur {}: {}", userIdentifier, e.getMessage(), e);
            return HttpResponse.badRequest(
                    VerificationResultDto.error("Erreur lors du traitement: " + e.getMessage())
            );
        }
    }

    @Get("/result/{requestId}")
    @Secured({"ADMIN", "VERIFICATION_USER", "VIEWER"})
    public HttpResponse<VerificationResultDto> getResult(@PathVariable String requestId) {
        try {
            log.debug("Recherche résultat pour requestId: {}", requestId);

            var result = resultService.getResult(requestId);
            if (result == null) {
                log.debug("Aucun résultat trouvé pour requestId: {}", requestId);
                return HttpResponse.notFound();
            }

            VerificationResultDto dto = resultService.convertToDto(result);
            return HttpResponse.ok(dto);

        } catch (Exception e) {
            log.error("Erreur récupération résultat pour requestId {}: {}", requestId, e.getMessage(), e);
            return HttpResponse.badRequest(
                    VerificationResultDto.error("Erreur récupération résultat: " + e.getMessage())
            );
        }
    }

    @Get("/history/{userIdentifier}")
    @Secured("ADMIN")
    public HttpResponse<java.util.List<VerificationResultDto>> getUserHistory(@PathVariable String userIdentifier) {
        try {
            log.debug("Recherche historique pour utilisateur: {}", userIdentifier);

            if (!resultService.userHasVerificationHistory(userIdentifier)) {
                log.debug("Aucun historique trouvé pour utilisateur: {}", userIdentifier);
                return HttpResponse.notFound();
            }

            var results = resultService.getUserVerificationHistory(userIdentifier);
            var dtos = results.stream()
                    .map(resultService::convertToDto)
                    .toList();

            log.info("Historique récupéré avec succès pour utilisateur: {} - {} résultats",
                    userIdentifier, dtos.size());
            return HttpResponse.ok(dtos);

        } catch (Exception e) {
            log.error("Erreur récupération historique pour utilisateur {}: {}", userIdentifier, e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    @Get("/stats/today")
    @Secured("ADMIN")
    public HttpResponse<VerificationResultService.VerificationStats> getTodayStats() {
        try {
            log.debug("Récupération statistiques du jour");
            var stats = resultService.getTodayStats();
            log.info("Statistiques du jour récupérées: {} vérifications", stats.getTotalVerifications());
            return HttpResponse.ok(stats);
        } catch (Exception e) {
            log.error("Erreur récupération statistiques du jour: {}", e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    @Get("/health")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<String> health() {
        log.debug("Health check appelé");
        return HttpResponse.ok("Identity Verification Service is running");
    }

    /**
     * Télécharger un fichier par son ID
     */
    @Get("/files/{fileId}/download")
    @Secured({"ADMIN", "VERIFICATION_USER"})
    public HttpResponse<StreamedFile> downloadFile(@PathVariable UUID fileId) {
        try {
            log.debug("Demande téléchargement fichier: {}", fileId);

            FileManagementService.FileDownloadResult result = fileManagementService.downloadFile(fileId);

            if (!result.isSuccess()) {
                log.warn("Échec téléchargement fichier {}: {}", fileId, result.getError());
                return HttpResponse.notFound();
            }

            log.info("Téléchargement fichier réussi: {} -> {}", fileId, result.getOriginalFilename());

            return HttpResponse.ok(result.getStreamedFile())
                    .header("Content-Disposition", "attachment; filename=\"" + result.getOriginalFilename() + "\"")
                    .contentType(MediaType.of(result.getContentType()));

        } catch (Exception e) {
            log.error("Erreur téléchargement fichier {}: {}", fileId, e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    /**
     * Lister les fichiers d'une session
     */
    @Get("/files/session/{sessionId}")
    @Secured("ADMIN")
    public HttpResponse<List<FileInfoDto>> getSessionFiles(@PathVariable String sessionId) {
        try {
            log.debug("Récupération fichiers pour session: {}", sessionId);

            List<FileInfoDto> files = fileManagementService.getSessionFiles(sessionId);

            log.info("Fichiers session {} récupérés: {} fichiers", sessionId, files.size());
            return HttpResponse.ok(files);

        } catch (Exception e) {
            log.error("Erreur récupération fichiers session {}: {}", sessionId, e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    /**
     * Lister les fichiers d'un résultat
     */
    @Get("/files/result/{requestId}")
    @Secured("ADMIN")
    public HttpResponse<List<FileInfoDto>> getResultFiles(@PathVariable String requestId) {
        try {
            log.debug("Récupération fichiers pour résultat: {}", requestId);

            List<FileInfoDto> files = fileManagementService.getResultFiles(requestId);

            log.info("Fichiers résultat {} récupérés: {} fichiers", requestId, files.size());
            return HttpResponse.ok(files);

        } catch (Exception e) {
            log.error("Erreur récupération fichiers résultat {}: {}", requestId, e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    /**
     * Obtenir les métadonnées d'un fichier
     */
    @Get("/files/{fileId}/info")
    @Secured("ADMIN")
    public HttpResponse<FileInfoDto> getFileInfo(@PathVariable UUID fileId) {
        try {
            log.debug("Récupération info fichier: {}", fileId);

            FileInfoDto fileInfo = fileManagementService.getFileInfo(fileId);

            if (fileInfo == null) {
                log.warn("Fichier non trouvé: {}", fileId);
                return HttpResponse.notFound();
            }

            log.debug("Info fichier {} récupérée", fileId);
            return HttpResponse.ok(fileInfo);

        } catch (Exception e) {
            log.error("Erreur récupération info fichier {}: {}", fileId, e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    /**
     * Supprimer un fichier
     */
    @Delete("/files/{fileId}")
    @Secured("ADMIN")
    public HttpResponse<String> deleteFile(@PathVariable UUID fileId) {
        try {
            log.debug("Demande suppression fichier: {}", fileId);

            boolean deleted = fileManagementService.deleteFile(fileId);

            if (!deleted) {
                log.warn("Fichier non trouvé pour suppression: {}", fileId);
                return HttpResponse.notFound("Fichier non trouvé");
            }

            log.info("Fichier supprimé avec succès: {}", fileId);
            return HttpResponse.ok("Fichier supprimé avec succès");

        } catch (Exception e) {
            log.error("Erreur suppression fichier {}: {}", fileId, e.getMessage(), e);
            return HttpResponse.serverError("Erreur lors de la suppression");
        }
    }

}