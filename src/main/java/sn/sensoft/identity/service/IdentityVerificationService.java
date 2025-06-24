package sn.sensoft.identity.service;

import sn.sensoft.identity.dto.VerificationResultDto;
import sn.sensoft.identity.util.FileValidator;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

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
    private VerificationSessionService sessionService; // Ajouté pour générer les IDs

    public VerificationResultDto processVerification(String userIdentifier,
                                                     CompletedFileUpload identityDocument,
                                                     CompletedFileUpload userPhoto) {
        try {
            // Valider les fichiers
            String docValidation = fileValidator.getValidationError(identityDocument);
            if (docValidation != null) {
                return VerificationResultDto.error("Document d'identité: " + docValidation);
            }

            String photoValidation = fileValidator.getValidationError(userPhoto);
            if (photoValidation != null) {
                return VerificationResultDto.error("Photo utilisateur: " + photoValidation);
            }

            // Sauvegarder les fichiers
            String docPath = fileStorageService.saveIdentityDocument(identityDocument);
            String photoPath = fileStorageService.saveUserPhoto(userPhoto);

            System.out.println("Début de l'extraction du document: " + docPath);

            // Extraction des données du document
            DocumentExtractionService.DocumentExtractionResult documentExtraction = null;
            try {
                documentExtraction = documentExtractionService.extractDocumentData(docPath);
                System.out.println("Extraction document: " + (documentExtraction.isSuccessful() ? "SUCCÈS" : "ÉCHEC"));
            } catch (Exception e) {
                System.out.println("Erreur extraction document: " + e.getMessage());
                // On continue même si l'extraction échoue
            }

            System.out.println("Début de la comparaison faciale");

            // Comparaison des visages
            FaceComparisonService.FaceComparisonResult faceComparison =
                    faceComparisonService.compareImages(docPath, photoPath);

            System.out.println("Comparaison faciale terminée: " + faceComparison.getConfidence());

            // Génération d'un ID court pour la requête
            String requestId = sessionService.generateShortRequestId();

            // Si extraction réussie, alors format enrichi
            if (documentExtraction != null && documentExtraction.isSuccessful()) {
                return VerificationResultDto.successWithData(
                        requestId,
                        userIdentifier,
                        faceComparison.getConfidence(),
                        faceComparison.isVerified(),
                        documentExtraction.getExtractedData(),
                        documentExtraction.getDocumentType(),
                        documentExtraction.getIssuingCountry()
                );
            } else {
                // Fallback : format simple si extraction échoue
                VerificationResultDto result = VerificationResultDto.success(
                        requestId,
                        userIdentifier,
                        faceComparison.getConfidence(),
                        faceComparison.isVerified()
                );

                // Ajouter l'info d'erreur d'extraction si disponible
                if (documentExtraction != null && !documentExtraction.isSuccessful()) {
                    result.setMessage("Comparaison faciale réussie. Extraction document échouée: " +
                            documentExtraction.getError());
                }

                return result;
            }

        } catch (Exception e) {
            System.out.println("Erreur globale: " + e.getMessage());
            return VerificationResultDto.error("Erreur lors de la vérification: " + e.getMessage());
        }
    }
}