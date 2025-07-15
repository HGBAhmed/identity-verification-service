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

    /**
     * Upload et extraction
     */
    @Post(value = "/document", consumes = MediaType.MULTIPART_FORM_DATA)
    @Secured({"VERIFICATION_USER", "ADMIN"})
    public HttpResponse<DocumentUploadResponse> uploadDocument(
            @Part("identityDocument") CompletedFileUpload identityDocument,
            @Part("userIdentifier") String userIdentifier) {

        try {
            log.debug("Début upload document pour utilisateur: {}", userIdentifier);

            // Validation du fichier
            String docValidation = fileValidator.getValidationError(identityDocument);
            if (docValidation != null) {
                log.warn("Validation échouée pour document utilisateur {}: {}", userIdentifier, docValidation);
                return HttpResponse.badRequest(
                        DocumentUploadResponse.error("Document d'identité: " + docValidation)
                );
            }

            // Utilise la méthode
            IdentityVerificationService.DocumentProcessingResult result =
                    verificationService.processDocumentOnly(userIdentifier, identityDocument);

            if (!result.isSuccess()) {
                log.warn("Échec traitement document pour utilisateur {}: {}", userIdentifier, result.getError());
                return HttpResponse.badRequest(
                        DocumentUploadResponse.error(result.getError())
                );
            }

            log.info("Document traité avec succès - Session: {} pour utilisateur: {}", result.getSessionId(), userIdentifier);

            // Préparer la réponse
            DocumentExtractionData responseData = new DocumentExtractionData();
            responseData.setDocumentType(result.getDocumentType());
            responseData.setIssuingCountry(result.getIssuingCountry());
            responseData.setExtractedFields(result.getExtractedData());

            DocumentUploadResponse response = DocumentUploadResponse.success(result.getSessionId(), responseData);

            return HttpResponse.ok(response);

        } catch (Exception e) {
            log.error("Erreur globale upload document pour utilisateur {}: {}", userIdentifier, e.getMessage(), e);
            return HttpResponse.badRequest(
                    DocumentUploadResponse.error("Erreur lors du traitement: " + e.getMessage())
            );
        }
    }

    /**
     * Upload photo et comparaison
     */
    @Post(value = "/compare/{documentId}", consumes = MediaType.MULTIPART_FORM_DATA)
    @Secured({"VERIFICATION_USER", "ADMIN"})
    public HttpResponse<VerificationResultDto> compareWithPhoto(
            @PathVariable String documentId,
            @Part("userPhoto") CompletedFileUpload userPhoto) {

        try {
            log.debug("Début comparaison photo pour document: {}", documentId);

            // Validation de la photo
            String photoValidation = fileValidator.getValidationError(userPhoto);
            if (photoValidation != null) {
                log.warn("Validation échouée pour photo document {}: {}", documentId, photoValidation);
                return HttpResponse.badRequest(
                        VerificationResultDto.error("Photo utilisateur: " + photoValidation)
                );
            }

            // Utilise la méthode
            VerificationResultDto result = verificationService.processPhotoComparison(documentId, userPhoto);

            if ("FAILED".equals(result.getStatus())) {
                log.warn("Comparaison échouée pour document: {}", documentId);
                return HttpResponse.badRequest(result);
            }

            log.info("Comparaison terminée avec succès - Request ID: {} pour document: {}", result.getRequestId(), documentId);
            return HttpResponse.ok(result);

        } catch (Exception e) {
            log.error("Erreur globale comparaison pour document {}: {}", documentId, e.getMessage(), e);
            return HttpResponse.badRequest(
                    VerificationResultDto.error("Erreur lors de la comparaison: " + e.getMessage())
            );
        }
    }

    /**
     * Upload complet
     */
    @Post(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA)
    @Secured({"VERIFICATION_USER", "ADMIN"})
    public HttpResponse<VerificationResultDto> uploadAndVerify(
            @Part("identityDocument") CompletedFileUpload identityDocument,
            @Part("userPhoto") CompletedFileUpload userPhoto,
            @Part("userIdentifier") String userIdentifier) {

        try {
            log.debug("Début vérification complète pour utilisateur: {}", userIdentifier);

            VerificationResultDto result = verificationService.processVerification(
                    userIdentifier, identityDocument, userPhoto
            );

            if ("FAILED".equals(result.getStatus())) {
                log.warn("Vérification complète échouée pour utilisateur: {}", userIdentifier);
                return HttpResponse.badRequest(result);
            }

            log.info("Vérification complète réussie pour utilisateur: {} - Request ID: {}", userIdentifier, result.getRequestId());
            return HttpResponse.ok(result);

        } catch (Exception e) {
            log.error("Erreur globale vérification complète pour utilisateur {}: {}", userIdentifier, e.getMessage(), e);
            return HttpResponse.badRequest(
                    VerificationResultDto.error("Erreur lors du traitement: " + e.getMessage())
            );
        }
    }

    /**
     * Récupérer un résultat par son ID
     */
    @Get("/result/{requestId}")
    @Secured({"ADMIN", "VERIFICATION_USER", "VIEWER"})
    public HttpResponse<VerificationResultDto> getResult(@PathVariable String requestId) {
        try {
            log.debug("Recherche résultat pour requestId: {}", requestId);

            var result = resultService.getResult(requestId);
            log.debug("Résultat trouvé: {}", (result != null));

            if (result == null) {
                log.debug("Aucun résultat trouvé pour requestId: {}", requestId);
                return HttpResponse.notFound();
            }

            log.debug("Détails résultat - ID: {}, Request ID: {}, User ID: {}",
                    result.getId(), result.getRequestId(), result.getUserIdentifier());

            log.debug("Conversion en DTO pour requestId: {}", requestId);
            VerificationResultDto dto = resultService.convertToDto(result);
            log.debug("DTO créé avec succès pour requestId: {}", requestId);

            return HttpResponse.ok(dto);

        } catch (Exception e) {
            log.error("Erreur détaillée récupération résultat pour requestId {}: {} - {}",
                    requestId, e.getClass().getSimpleName(), e.getMessage(), e);
            return HttpResponse.badRequest(
                    VerificationResultDto.error("Erreur récupération résultat: " + e.getMessage())
            );
        }
    }

    /**
     * Historique des vérifications d'un utilisateur
     */
    @Get("/history/{userIdentifier}")
    @Secured({"ADMIN", "VERIFICATION_USER", "VIEWER"})
    public HttpResponse<java.util.List<VerificationResultDto>> getUserHistory(@PathVariable String userIdentifier) {
        try {
            log.debug("Recherche historique pour utilisateur: {}", userIdentifier);

            // Vérifier si l'utilisateur a déjà fait des vérifications
            if (!resultService.userHasVerificationHistory(userIdentifier)) {
                log.debug("Aucun historique trouvé pour utilisateur: {}", userIdentifier);
                return HttpResponse.notFound();
            }

            var results = resultService.getUserVerificationHistory(userIdentifier);
            var dtos = results.stream()
                    .map(resultService::convertToDto)
                    .toList();

            log.info("Historique récupéré avec succès pour utilisateur: {} - {} résultats", userIdentifier, dtos.size());
            return HttpResponse.ok(dtos);

        } catch (Exception e) {
            log.error("Erreur récupération historique pour utilisateur {}: {}", userIdentifier, e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    /**
     * Statistiques de vérification
     */
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

    /**
     * Statistiques de vérification
     */
    @Get("/stats/week")
    @Secured("ADMIN")
    public HttpResponse<VerificationResultService.VerificationStats> getWeekStats() {
        try {
            log.debug("Récupération statistiques de la semaine");
            var stats = resultService.getWeekStats();
            log.info("Statistiques de la semaine récupérées: {} vérifications", stats.getTotalVerifications());
            return HttpResponse.ok(stats);
        } catch (Exception e) {
            log.error("Erreur récupération statistiques de la semaine: {}", e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    /**
     * Health check
     */
    @Get("/health")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<String> health() {
        log.debug("Health check appelé");
        return HttpResponse.ok("Identity Verification Service is running");
    }

    /**
     * Nombre de sessions
     */
    @Get("/sessions/count")
    @Secured("ADMIN")
    public HttpResponse<String> getSessionsCount() {
        int count = sessionService.getActiveSessionsCount();
        log.info("Nombre de sessions actives: {}", count);
        return HttpResponse.ok("Sessions actives: " + count);
    }

    /**
     * Endpoint de diagnostic pour OpenKM
     */
    @Get("/status/storage")
    @Secured("ADMIN")
    public HttpResponse<java.util.Map<String, Object>> getStorageStatus() {
        try {
            log.debug("Récupération statut de stockage");
            var status = new java.util.HashMap<String, Object>();
            status.put("openKMEnabled", true); // À récupérer depuis FileStorageService
            status.put("activeSessionsCount", sessionService.getActiveSessionsCount());
            status.put("todayStats", resultService.getTodayStats());

            log.info("Statut de stockage récupéré avec succès");
            return HttpResponse.ok(status);
        } catch (Exception e) {
            log.error("Erreur récupération statut de stockage: {}", e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }
}