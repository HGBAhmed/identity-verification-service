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
import jakarta.inject.Inject;

@Controller("/api/v1/verification")
@ExecuteOn(TaskExecutors.BLOCKING)
public class IdentityVerificationController {

    @Inject
    private IdentityVerificationService verificationService;

    @Inject
    private VerificationSessionService sessionService;

    @Inject
    private VerificationResultService resultService;

    @Inject
    private FileValidator fileValidator;

    /**
     * Upload et extraction (adaptation légère)
     */
    @Post(value = "/document", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<DocumentUploadResponse> uploadDocument(
            @Part("identityDocument") CompletedFileUpload identityDocument,
            @Part("userIdentifier") String userIdentifier) {

        try {
            // Validation du fichier
            String docValidation = fileValidator.getValidationError(identityDocument);
            if (docValidation != null) {
                return HttpResponse.badRequest(
                        DocumentUploadResponse.error("Document d'identité: " + docValidation)
                );
            }

            // Utiliser la nouvelle méthode
            IdentityVerificationService.DocumentProcessingResult result =
                    verificationService.processDocumentOnly(userIdentifier, identityDocument);

            if (!result.isSuccess()) {
                return HttpResponse.badRequest(
                        DocumentUploadResponse.error(result.getError())
                );
            }

            System.out.println(" Document traité avec succès - Session: " + result.getSessionId());

            // Préparer la réponse
            DocumentExtractionData responseData = new DocumentExtractionData();
            responseData.setDocumentType(result.getDocumentType());
            responseData.setIssuingCountry(result.getIssuingCountry());
            responseData.setExtractedFields(result.getExtractedData());

            DocumentUploadResponse response = DocumentUploadResponse.success(result.getSessionId(), responseData);

            return HttpResponse.ok(response);

        } catch (Exception e) {
            System.out.println(" Erreur globale upload document: " + e.getMessage());
            return HttpResponse.badRequest(
                    DocumentUploadResponse.error("Erreur lors du traitement: " + e.getMessage())
            );
        }
    }

    /**
     * Upload photo et comparaison
     */
    @Post(value = "/compare/{documentId}", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<VerificationResultDto> compareWithPhoto(
            @PathVariable String documentId,
            @Part("userPhoto") CompletedFileUpload userPhoto) {

        try {
            // Validation de la photo
            String photoValidation = fileValidator.getValidationError(userPhoto);
            if (photoValidation != null) {
                return HttpResponse.badRequest(
                        VerificationResultDto.error("Photo utilisateur: " + photoValidation)
                );
            }

            // Utiliser la nouvelle méthode
            VerificationResultDto result = verificationService.processPhotoComparison(documentId, userPhoto);

            if ("FAILED".equals(result.getStatus())) {
                return HttpResponse.badRequest(result);
            }

            System.out.println(" Comparaison terminée - Request ID: " + result.getRequestId());
            return HttpResponse.ok(result);

        } catch (Exception e) {
            System.out.println(" Erreur globale comparaison: " + e.getMessage());
            return HttpResponse.badRequest(
                    VerificationResultDto.error("Erreur lors de la comparaison: " + e.getMessage())
            );
        }
    }

    @Post(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<VerificationResultDto> uploadAndVerify(
            @Part("identityDocument") CompletedFileUpload identityDocument,
            @Part("userPhoto") CompletedFileUpload userPhoto,
            @Part("userIdentifier") String userIdentifier) {

        try {
            VerificationResultDto result = verificationService.processVerification(
                    userIdentifier, identityDocument, userPhoto
            );

            if ("FAILED".equals(result.getStatus())) {
                return HttpResponse.badRequest(result);
            }

            return HttpResponse.ok(result);
        } catch (Exception e) {
            return HttpResponse.badRequest(
                    VerificationResultDto.error("Erreur lors du traitement: " + e.getMessage())
            );
        }
    }

    /**
     *  Récupérer un résultat par son ID
     */
    @Get("/result/{requestId}")
    public HttpResponse<VerificationResultDto> getResult(@PathVariable String requestId) {
        try {

            System.out.println("Recherche résultat pour requestId: " + requestId);

            var result = resultService.getResult(requestId);
            System.out.println("Résultat trouvé: " + (result != null));

            if (result == null) {
                System.out.println("Aucun résultat trouvé");
                return HttpResponse.notFound();
            }

            System.out.println("ID du résultat: " + result.getId());
            System.out.println("Request ID: " + result.getRequestId());
            System.out.println("User ID: " + result.getUserIdentifier());

            System.out.println("Conversion en DTO...");
            VerificationResultDto dto = resultService.convertToDto(result);
            System.out.println("DTO créé avec succès");

            return HttpResponse.ok(dto);

        } catch (Exception e) {
            System.err.println("Erreur détaillée: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            return HttpResponse.badRequest(
                    VerificationResultDto.error("Erreur récupération résultat: " + e.getMessage())
            );
        }
    }

    /**
     * Historique des vérifications d'un utilisateur
     */
    @Get("/history/{userIdentifier}")
    public HttpResponse<java.util.List<VerificationResultDto>> getUserHistory(@PathVariable String userIdentifier) {
        try {
            var results = resultService.getUserVerificationHistory(userIdentifier);
            var dtos = results.stream()
                    .map(resultService::convertToDto)
                    .toList();

            return HttpResponse.ok(dtos);

        } catch (Exception e) {
            return HttpResponse.serverError();
        }
    }

    /**
     * Statistiques de vérification
     */
    @Get("/stats/today")
    public HttpResponse<VerificationResultService.VerificationStats> getTodayStats() {
        try {
            var stats = resultService.getTodayStats();
            return HttpResponse.ok(stats);
        } catch (Exception e) {
            return HttpResponse.serverError();
        }
    }

    @Get("/stats/week")
    public HttpResponse<VerificationResultService.VerificationStats> getWeekStats() {
        try {
            var stats = resultService.getWeekStats();
            return HttpResponse.ok(stats);
        } catch (Exception e) {
            return HttpResponse.serverError();
        }
    }

    @Get("/health")
    public HttpResponse<String> health() {
        return HttpResponse.ok("Identity Verification Service is running");
    }

    @Get("/sessions/count")
    public HttpResponse<String> getSessionsCount() {
        int count = sessionService.getActiveSessionsCount();
        return HttpResponse.ok("Sessions actives: " + count);
    }

    /**
     *  Endpoint de diagnostic OpenKM
     */
    @Get("/status/storage")
    public HttpResponse<java.util.Map<String, Object>> getStorageStatus() {
        try {
            var status = new java.util.HashMap<String, Object>();
            status.put("openKMEnabled", true); // À récupérer depuis FileStorageService
            status.put("activeSessionsCount", sessionService.getActiveSessionsCount());
            status.put("todayStats", resultService.getTodayStats());

            return HttpResponse.ok(status);
        } catch (Exception e) {
            return HttpResponse.serverError();
        }
    }
}