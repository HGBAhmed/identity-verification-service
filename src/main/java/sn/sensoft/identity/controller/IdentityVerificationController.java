package sn.sensoft.identity.controller;

import sn.sensoft.identity.dto.DocumentUploadResponse;
import sn.sensoft.identity.dto.DocumentSession;
import sn.sensoft.identity.dto.VerificationResultDto;
import sn.sensoft.identity.dto.VerificationResultDto.DocumentExtractionData;
import sn.sensoft.identity.service.DocumentExtractionService;
import sn.sensoft.identity.service.FaceComparisonService;
import sn.sensoft.identity.service.FileStorageService;
import sn.sensoft.identity.service.IdentityVerificationService;
import sn.sensoft.identity.service.VerificationSessionService;
import sn.sensoft.identity.util.FileValidator;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Inject;

@Controller("/api/v1/verification")
public class IdentityVerificationController {

    @Inject
    private IdentityVerificationService verificationService;

    @Inject
    private VerificationSessionService sessionService;

    @Inject
    private FileStorageService fileStorageService;

    @Inject
    private DocumentExtractionService documentExtractionService;

    @Inject
    private FaceComparisonService faceComparisonService;

    @Inject
    private FileValidator fileValidator;


    /**
     * Upload et extraction
     */
    @Post(value = "/document", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<DocumentUploadResponse> uploadDocument(
            @Part("identityDocument") CompletedFileUpload identityDocument,
            @Part("userIdentifier") String userIdentifier) {

        try {
            // 1. Validation du fichier
            String docValidation = fileValidator.getValidationError(identityDocument);
            if (docValidation != null) {
                return HttpResponse.badRequest(
                        DocumentUploadResponse.error("Document d'identité: " + docValidation)
                );
            }

            // 2. Sauvegarde du document
            String docPath = fileStorageService.saveIdentityDocument(identityDocument);
            System.out.println(" Document sauvegardé: " + docPath);

            // 3. Extraction des données du document
            DocumentExtractionService.DocumentExtractionResult extraction;
            try {
                extraction = documentExtractionService.extractDocumentData(docPath);

                if (!extraction.isSuccessful()) {
                    return HttpResponse.badRequest(
                            DocumentUploadResponse.error("Échec de l'extraction: " + extraction.getError())
                    );
                }

                System.out.println(" Extraction réussie pour: " + extraction.getDocumentType());

            } catch (Exception e) {
                System.out.println(" Erreur extraction: " + e.getMessage());
                return HttpResponse.badRequest(
                        DocumentUploadResponse.error("Erreur lors de l'extraction: " + e.getMessage())
                );
            }

            //  Création de la session temporaire
            String documentId = sessionService.createDocumentSession(
                    userIdentifier, docPath,
                    extraction.getDocumentType(),
                    extraction.getIssuingCountry(),
                    extraction.getExtractedData()
            );

            // Préparation de la réponse
            VerificationResultDto.DocumentExtractionData responseData =
                    new VerificationResultDto.DocumentExtractionData();
            responseData.setDocumentType(extraction.getDocumentType());
            responseData.setIssuingCountry(extraction.getIssuingCountry());
            responseData.setExtractedFields(extraction.getExtractedData());

            DocumentUploadResponse response = DocumentUploadResponse.success(documentId, responseData);

            return HttpResponse.ok(response);

        } catch (Exception e) {
            System.out.println(" Erreur globale upload document: " + e.getMessage());
            return HttpResponse.badRequest(
                    DocumentUploadResponse.error("Erreur lors du traitement: " + e.getMessage())
            );
        }
    }

    /**
     *  Upload photo et comparaison
     */
    @Post(value = "/compare/{documentId}", consumes = MediaType.MULTIPART_FORM_DATA)
    public HttpResponse<VerificationResultDto> compareWithPhoto(
            @PathVariable String documentId,
            @Part("userPhoto") CompletedFileUpload userPhoto) {

        try {
            // 1. Récupération de la session
            DocumentSession session = sessionService.getSession(documentId);
            if (session == null) {
                return HttpResponse.badRequest(
                        VerificationResultDto.error("Session non trouvée ou expirée: " + documentId)
                );
            }

            // 2. Validation de la photo
            String photoValidation = fileValidator.getValidationError(userPhoto);
            if (photoValidation != null) {
                return HttpResponse.badRequest(
                        VerificationResultDto.error("Photo utilisateur: " + photoValidation)
                );
            }

            // 3. Sauvegarde de la photo
            String photoPath = fileStorageService.saveUserPhoto(userPhoto);
            System.out.println(" Photo sauvegardée: " + photoPath);

            // 4. Comparaison faciale
            System.out.println(" Début comparaison faciale...");
            FaceComparisonService.FaceComparisonResult faceComparison =
                    faceComparisonService.compareImages(session.getDocumentPath(), photoPath);

            System.out.println(" Comparaison terminée - Confiance: " + faceComparison.getConfidence());

            // 5. Construction du résultat final
            String requestId = sessionService.generateShortRequestId();

            VerificationResultDto result = VerificationResultDto.successWithData(
                    requestId, // Utilisation directe du String
                    session.getUserIdentifier(),
                    faceComparison.getConfidence(),
                    faceComparison.isVerified(),
                    session.getExtractedData(),
                    session.getDocumentType(),
                    session.getIssuingCountry()
            );

            // 6. Nettoyage de la session
            sessionService.removeSession(documentId);

            return HttpResponse.ok(result);

        } catch (Exception e) {
            System.out.println(" Erreur globale comparaison: " + e.getMessage());
            return HttpResponse.badRequest(
                    VerificationResultDto.error("Erreur lors de la comparaison: " + e.getMessage())
            );
        }
    }

    /**
     * Ancien endpoint tout-en-un pour compatibilité
     */
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


    @Get("/health")
    public HttpResponse<String> health() {
        return HttpResponse.ok("Identity Verification Service is running");
    }

    @Get("/sessions/count")
    public HttpResponse<String> getSessionsCount() {
        int count = sessionService.getActiveSessionsCount();
        return HttpResponse.ok("Sessions actives: " + count);
    }
}