package sn.sensoft.identity.service;

import sn.sensoft.identity.dto.VerificationResultDto;
import sn.sensoft.identity.entity.VerificationRequest;
import sn.sensoft.identity.repository.VerificationRepository;
import sn.sensoft.identity.util.FileValidator;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Singleton
public class IdentityVerificationService {

    @Inject
    private VerificationRepository verificationRepository;

    @Inject
    private FileStorageService fileStorageService;

    @Inject
    private FaceComparisonService faceComparisonService;

    @Inject
    private FileValidator fileValidator;

    @Transactional
    public VerificationResultDto processVerification(String userIdentifier,
                                                     CompletedFileUpload identityDocument,
                                                     CompletedFileUpload userPhoto) {
        // Valider les fichiers
        String docValidation = fileValidator.getValidationError(identityDocument);
        if (docValidation != null) {
            return VerificationResultDto.error("Document d'identité: " + docValidation);
        }

        String photoValidation = fileValidator.getValidationError(userPhoto);
        if (photoValidation != null) {
            return VerificationResultDto.error("Photo utilisateur: " + photoValidation);
        }

        // Créer une nouvelle demande
        VerificationRequest request = new VerificationRequest(userIdentifier);
        request = verificationRepository.save(request);

        try {
            // Sauvegarder les fichiers
            request.setStatus("PROCESSING");
            verificationRepository.update(request);

            String docPath = fileStorageService.saveIdentityDocument(identityDocument);
            String photoPath = fileStorageService.saveUserPhoto(userPhoto);

            request.setIdentityDocumentPath(docPath);
            request.setUserPhotoPath(photoPath);
            verificationRepository.update(request);

            // Comparer les visages
            FaceComparisonService.FaceComparisonResult comparison =
                    faceComparisonService.compareImages(docPath, photoPath);

            // Mettre à jour avec les résultats
            request.setStatus("COMPLETED");
            request.setConfidenceScore(comparison.getConfidence());
            request.setIsMatch(comparison.isVerified());

            if (comparison.getError() != null) {
                request.setErrorMessage(comparison.getError());
            }

            request = verificationRepository.update(request);

            return VerificationResultDto.success(
                    request.getId(),
                    userIdentifier,
                    comparison.getConfidence(),
                    comparison.isVerified()
            );

        } catch (Exception e) {
            request.setStatus("FAILED");
            request.setErrorMessage(e.getMessage());
            verificationRepository.update(request);

            return VerificationResultDto.error("Erreur lors de la vérification: " + e.getMessage());
        }
    }

    public Optional<VerificationResultDto> getVerificationResult(UUID requestId) {
        return verificationRepository.findById(requestId)
                .map(this::toDto);
    }

    public List<VerificationResultDto> getUserHistory(String userIdentifier) {
        return verificationRepository.findByUserIdentifierOrderByCreatedAtDesc(userIdentifier)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private VerificationResultDto toDto(VerificationRequest request) {
        VerificationResultDto dto = new VerificationResultDto();
        dto.setRequestId(request.getId());
        dto.setUserIdentifier(request.getUserIdentifier());
        dto.setStatus(request.getStatus());
        dto.setConfidenceScore(request.getConfidenceScore());
        dto.setIsMatch(request.getIsMatch());
        dto.setMessage(request.getErrorMessage());
        dto.setCreatedAt(request.getCreatedAt());
        dto.setUpdatedAt(request.getUpdatedAt());
        return dto;
    }
}