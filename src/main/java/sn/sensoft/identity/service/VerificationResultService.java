package sn.sensoft.identity.service;

import sn.sensoft.identity.dto.VerificationResultDto;
import sn.sensoft.identity.entity.VerificationResult;
import sn.sensoft.identity.entity.VerificationSession;
import sn.sensoft.identity.repository.VerificationResultRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Singleton
public class VerificationResultService {

    private final VerificationResultRepository resultRepository;
    private final VerificationSessionService sessionService;

    public VerificationResultService(VerificationResultRepository resultRepository,
                                     VerificationSessionService sessionService) {
        this.resultRepository = resultRepository;
        this.sessionService = sessionService;
    }

    // ===================================
    // SAUVEGARDE DES RÉSULTATS
    // ===================================

    /**
     * Sauvegarde un résultat de vérification complet
     */
    @Transactional
    public VerificationResult saveVerificationResult(VerificationResultDto resultDto,
                                                     String sessionId,
                                                     UUID documentFileId,
                                                     UUID photoFileId) {
        try {
            VerificationResult result = new VerificationResult(
                    resultDto.getRequestId(),
                    resultDto.getUserIdentifier()
            );

            // Données de base
            result.setSessionId(sessionId);
            result.setStatus(convertStatus(resultDto.getStatus()));
            result.setMessage(resultDto.getMessage());

            // Résultats de comparaison faciale
            if (resultDto.getFaceComparison() != null) {
                result.setFaceConfidenceScore(BigDecimal.valueOf(resultDto.getFaceComparison().getConfidenceScore()));
                result.setFaceIsMatch(resultDto.getFaceComparison().getIsMatch());

                // Stocker les données techniques de comparaison
                Map<String, Object> faceData = new HashMap<>();
                faceData.put("confidence", resultDto.getFaceComparison().getConfidenceScore());
                faceData.put("isMatch", resultDto.getFaceComparison().getIsMatch());
                faceData.put("message", resultDto.getFaceComparison().getMessage());
                faceData.put("timestamp", LocalDateTime.now());
                result.setFaceComparisonData(faceData);
            }

            // Données d'extraction de document
            if (resultDto.getDocumentData() != null) {
                Map<String, Object> docData = new HashMap<>();
                docData.put("documentType", resultDto.getDocumentData().getDocumentType());
                docData.put("issuingCountry", resultDto.getDocumentData().getIssuingCountry());
                docData.put("extractedFields", resultDto.getDocumentData().getExtractedFields());
                docData.put("timestamp", LocalDateTime.now());
                result.setDocumentExtractionData(docData);
            }

            // Références aux fichiers
            result.setDocumentFileId(documentFileId);
            result.setPhotoFileId(photoFileId);

            // Timestamps
            result.setCreatedAt(LocalDateTime.now());
            result.setUpdatedAt(LocalDateTime.now());

            // Sauvegarder
            result = resultRepository.save(result);

            System.out.println(" Résultat sauvegardé: " + result.getRequestId() +
                    " (Match: " + result.getFaceIsMatch() +
                    ", Confiance: " + result.getConfidenceAsDouble() + ")");

            return result;

        } catch (Exception e) {
            System.err.println(" Erreur sauvegarde résultat: " + e.getMessage());
            throw new RuntimeException("Erreur sauvegarde résultat", e);
        }
    }

    /**
     * Sauvegarde d'un résultat d'erreur
     */
    @Transactional
    public VerificationResult saveErrorResult(String requestId, String userIdentifier,
                                              String errorMessage, String sessionId) {
        try {
            VerificationResult result = VerificationResult.createError(requestId, userIdentifier, errorMessage);
            result.setSessionId(sessionId);

            result = resultRepository.save(result);

            System.out.println(" Résultat d'erreur sauvegardé: " + requestId + " - " + errorMessage);

            return result;

        } catch (Exception e) {
            System.err.println(" Erreur sauvegarde résultat d'erreur: " + e.getMessage());
            throw new RuntimeException("Erreur sauvegarde résultat d'erreur", e);
        }
    }

    // ===================================
    // RECHERCHE ET CONSULTATION
    // ===================================

    /**
     * Récupère un résultat par son request ID
     */
    public VerificationResult getResult(String requestId) {
        return resultRepository.findByRequestId(requestId).orElse(null);
    }

    /**
     * Récupère l'historique des vérifications d'un utilisateur
     */
    public List<VerificationResult> getUserVerificationHistory(String userIdentifier) {
        return resultRepository.findByUserIdentifierOrderByCreatedAtDesc(userIdentifier);
    }

    /**
     * Récupère l'historique sur une période
     */
    public List<VerificationResult> getUserVerificationHistory(String userIdentifier,
                                                               LocalDateTime startDate,
                                                               LocalDateTime endDate) {
        return resultRepository.findByUserIdentifierAndCreatedAtBetween(userIdentifier, startDate, endDate);
    }

    /**
     * Récupère les résultats récents
     */
    public List<VerificationResult> getRecentResults() {
        return resultRepository.findTop10ByOrderByCreatedAtDesc();
    }

    /**
     * Récupère les résultats par statut
     */
    public List<VerificationResult> getResultsByStatus(VerificationResult.VerificationStatus status) {
        return resultRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    // ===================================
    // STATISTIQUES
    // ===================================

    /**
     * Statistiques de succès sur une période
     */
    public VerificationStats getStatsBetween(LocalDateTime startDate, LocalDateTime endDate) {
        long totalVerifications = resultRepository.countTotalVerificationsBetween(startDate, endDate);
        long successfulVerifications = resultRepository.countSuccessfulVerificationsBetween(startDate, endDate);
        Double avgConfidence = resultRepository.averageConfidenceScoreBetween(startDate, endDate).orElse(0.0);

        double successRate = totalVerifications > 0 ?
                (double) successfulVerifications / totalVerifications * 100 : 0.0;

        return new VerificationStats(
                totalVerifications,
                successfulVerifications,
                successRate,
                avgConfidence,
                startDate,
                endDate
        );
    }

    /**
     * Statistiques du jour
     */
    public VerificationStats getTodayStats() {
        LocalDateTime startOfDay = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        LocalDateTime endOfDay = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59);
        return getStatsBetween(startOfDay, endOfDay);
    }

    /**
     * Statistiques de la semaine
     */
    public VerificationStats getWeekStats() {
        LocalDateTime weekAgo = LocalDateTime.now().minusWeeks(1);
        return getStatsBetween(weekAgo, LocalDateTime.now());
    }

    // ===================================
    // NETTOYAGE ET MAINTENANCE
    // ===================================

    /**
     * Nettoie les anciens résultats selon la politique de rétention
     */
    @Transactional
    public int cleanupOldResults(int retentionDays) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
        int deletedCount = resultRepository.deleteByCreatedAtBefore(cutoffDate);

        if (deletedCount > 0) {
            System.out.println(" Nettoyage: " + deletedCount + " résultats anciens supprimés (> " + retentionDays + " jours)");
        }

        return deletedCount;
    }

    // ===================================
    // MÉTHODES UTILITAIRES
    // ===================================

    private VerificationResult.VerificationStatus convertStatus(String status) {
        if (status == null) return VerificationResult.VerificationStatus.COMPLETED;

        switch (status.toUpperCase()) {
            case "PROCESSING":
                return VerificationResult.VerificationStatus.PROCESSING;
            case "FAILED":
                return VerificationResult.VerificationStatus.FAILED;
            case "COMPLETED":
            default:
                return VerificationResult.VerificationStatus.COMPLETED;
        }
    }

    /**
     * Convertit un résultat persisté vers un DTO pour la réponse
     */
    /**
     * Version debug de convertToDto pour identifier le problème
     */
    public VerificationResultDto convertToDto(VerificationResult result) {
        if (result == null) return null;

        try {
            System.out.println(" Début conversion DTO");

            VerificationResultDto dto = new VerificationResultDto();

            System.out.println(" Setting requestId: " + result.getRequestId());
            dto.setRequestId(result.getRequestId());

            System.out.println(" Setting userIdentifier: " + result.getUserIdentifier());
            dto.setUserIdentifier(result.getUserIdentifier());

            System.out.println(" Setting status: " + result.getStatus());
            dto.setStatus(result.getStatus().name());

            System.out.println(" Setting message: " + result.getMessage());
            dto.setMessage(result.getMessage());

            System.out.println(" Setting createdAt...");
            dto.setCreatedAt(result.getCreatedAt());

            System.out.println(" Setting updatedAt...");
            dto.setUpdatedAt(result.getUpdatedAt());

            // Test des UUID - POTENTIEL PROBLÈME ICI
            System.out.println(" Checking documentFileId...");
            UUID docFileId = result.getDocumentFileId();
            System.out.println(" DocumentFileId: " + docFileId);

            System.out.println("Checking photoFileId...");
            UUID photoFileId = result.getPhotoFileId();
            System.out.println(" PhotoFileId: " + photoFileId);

            // Données de comparaison faciale
            System.out.println(" Processing face data...");
            if (result.getFaceConfidenceScore() != null) {
                VerificationResultDto.FaceComparisonData faceData = new VerificationResultDto.FaceComparisonData();
                faceData.setConfidenceScore(result.getConfidenceAsDouble());
                faceData.setIsMatch(result.getFaceIsMatch());
                faceData.setMessage(result.getMessage());
                dto.setFaceComparison(faceData);
            }

            // Données d'extraction de document
            System.out.println(" Processing document data...");
            if (result.getDocumentExtractionData() != null) {
                VerificationResultDto.DocumentExtractionData docData = new VerificationResultDto.DocumentExtractionData();

                Map<String, Object> extractionData = result.getDocumentExtractionData();
                if (extractionData.containsKey("documentType")) {
                    docData.setDocumentType((String) extractionData.get("documentType"));
                }
                if (extractionData.containsKey("issuingCountry")) {
                    docData.setIssuingCountry((String) extractionData.get("issuingCountry"));
                }
                if (extractionData.containsKey("extractedFields")) {
                    docData.setExtractedFields((Map<String, Object>) extractionData.get("extractedFields"));
                }

                dto.setDocumentData(docData);
            }

            System.out.println(" Conversion DTO terminée avec succès");
            return dto;

        } catch (Exception e) {
            System.err.println(" Erreur dans convertToDto: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    // ===================================
    // CLASSE STATISTIQUES
    // ===================================
    @io.micronaut.serde.annotation.Serdeable
    public static class VerificationStats {
        private final long totalVerifications;
        private final long successfulVerifications;
        private final double successRate;
        private final double averageConfidence;
        private final LocalDateTime startDate;
        private final LocalDateTime endDate;

        public VerificationStats(long totalVerifications, long successfulVerifications,
                                 double successRate, double averageConfidence,
                                 LocalDateTime startDate, LocalDateTime endDate) {
            this.totalVerifications = totalVerifications;
            this.successfulVerifications = successfulVerifications;
            this.successRate = successRate;
            this.averageConfidence = averageConfidence;
            this.startDate = startDate;
            this.endDate = endDate;
        }

        // Getters
        public long getTotalVerifications() { return totalVerifications; }
        public long getSuccessfulVerifications() { return successfulVerifications; }
        public double getSuccessRate() { return successRate; }
        public double getAverageConfidence() { return averageConfidence; }
        public LocalDateTime getStartDate() { return startDate; }
        public LocalDateTime getEndDate() { return endDate; }

        @Override
        public String toString() {
            return String.format("Stats[%d total, %d succès (%.1f%%), confiance moyenne: %.3f]",
                    totalVerifications, successfulVerifications, successRate, averageConfidence);
        }
    }
}