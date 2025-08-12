package sn.sensoft.identity.service;

import sn.sensoft.identity.dto.VerificationResultDto;
import sn.sensoft.identity.entity.VerificationResult;
import sn.sensoft.identity.entity.VerificationSession;
import sn.sensoft.identity.repository.VerificationResultRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Singleton
public class VerificationResultService {

    private static final Logger log = LoggerFactory.getLogger(VerificationResultService.class);

    private final VerificationResultRepository resultRepository;
    private final VerificationSessionService sessionService;

    public VerificationResultService(VerificationResultRepository resultRepository,
                                     VerificationSessionService sessionService) {
        this.resultRepository = resultRepository;
        this.sessionService = sessionService;
        log.info("VerificationResultService initialisé");
    }

    // SAUVEGARDE DES RÉSULTATS
    /**
     * Sauvegarde un résultat de vérification complet
     */
    @Transactional
    public VerificationResult saveVerificationResult(VerificationResultDto resultDto,
                                                     String sessionId,
                                                     UUID documentFileId,
                                                     UUID photoFileId) {
        try {
            log.debug("Sauvegarde résultat de vérification - RequestId: {}, Session: {}",
                    resultDto.getRequestId(), sessionId);

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

                log.debug("Données faciales sauvegardées - Match: {}, Confiance: {:.3f}",
                        resultDto.getFaceComparison().getIsMatch(),
                        resultDto.getFaceComparison().getConfidenceScore());
            }

            // Données d'extraction de document
            if (resultDto.getDocumentData() != null) {
                Map<String, Object> docData = new HashMap<>();
                docData.put("documentType", resultDto.getDocumentData().getDocumentType());
                docData.put("issuingCountry", resultDto.getDocumentData().getIssuingCountry());
                docData.put("extractedFields", resultDto.getDocumentData().getExtractedFields());
                docData.put("timestamp", LocalDateTime.now());
                result.setDocumentExtractionData(docData);

                log.debug("Données document sauvegardées - Type: {}, Pays: {}",
                        resultDto.getDocumentData().getDocumentType(),
                        resultDto.getDocumentData().getIssuingCountry());
            }

            // Références aux fichiers
            result.setDocumentFileId(documentFileId);
            result.setPhotoFileId(photoFileId);

            // Timestamps
            result.setCreatedAt(LocalDateTime.now());
            result.setUpdatedAt(LocalDateTime.now());

            // Sauvegarder
            result = resultRepository.save(result);

            log.info("Résultat sauvegardé avec succès - RequestId: {}, Match: {}, Confiance: {:.3f}",
                    result.getRequestId(), result.getFaceIsMatch(), result.getConfidenceAsDouble());

            return result;

        } catch (Exception e) {
            log.error("Erreur sauvegarde résultat pour RequestId {}: {}",
                    resultDto.getRequestId(), e.getMessage(), e);
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
            log.debug("Sauvegarde résultat d'erreur - RequestId: {}, Session: {}", requestId, sessionId);

            VerificationResult result = VerificationResult.createError(requestId, userIdentifier, errorMessage);
            result.setSessionId(sessionId);

            result = resultRepository.save(result);

            log.warn("Résultat d'erreur sauvegardé - RequestId: {}, Erreur: {}", requestId, errorMessage);

            return result;

        } catch (Exception e) {
            log.error("Erreur sauvegarde résultat d'erreur pour RequestId {}: {}", requestId, e.getMessage(), e);
            throw new RuntimeException("Erreur sauvegarde résultat d'erreur", e);
        }
    }

    // RECHERCHE ET CONSULTATION
    /**
     * Récupère un résultat par son request ID
     */
    public VerificationResult getResult(String requestId) {
        log.debug("Recherche résultat pour RequestId: {}", requestId);
        VerificationResult result = resultRepository.findByRequestId(requestId).orElse(null);

        if (result != null) {
            log.debug("Résultat trouvé pour RequestId: {}", requestId);
        } else {
            log.debug("Aucun résultat trouvé pour RequestId: {}", requestId);
        }

        return result;
    }

    /**
     * Récupère l'historique des vérifications d'un utilisateur
     */
    public List<VerificationResult> getUserVerificationHistory(String userIdentifier) {
        log.debug("Recherche historique pour utilisateur: {}", userIdentifier);
        List<VerificationResult> results = resultRepository.findByUserIdentifierOrderByCreatedAtDesc(userIdentifier);
        log.debug("Historique trouvé: {} résultats pour utilisateur: {}", results.size(), userIdentifier);
        return results;
    }

    /**
     * Vérifie si un utilisateur a déjà fait des vérifications
     */
    public boolean userHasVerificationHistory(String userIdentifier) {
        boolean hasHistory = resultRepository.existsByUserIdentifier(userIdentifier);
        log.debug("Utilisateur {} a un historique: {}", userIdentifier, hasHistory);
        return hasHistory;
    }

    /**
     * Récupère l'historique sur une période
     */
    public List<VerificationResult> getUserVerificationHistory(String userIdentifier,
                                                               LocalDateTime startDate,
                                                               LocalDateTime endDate) {
        log.debug("Recherche historique pour utilisateur: {} entre {} et {}", userIdentifier, startDate, endDate);
        return resultRepository.findByUserIdentifierAndCreatedAtBetween(userIdentifier, startDate, endDate);
    }

    /**
     * Récupère les résultats récents
     */
    public List<VerificationResult> getRecentResults() {
        log.debug("Récupération des résultats récents");
        return resultRepository.findTop10ByOrderByCreatedAtDesc();
    }

    /**
     * Récupère les résultats par statut
     */
    public List<VerificationResult> getResultsByStatus(VerificationResult.VerificationStatus status) {
        log.debug("Recherche résultats par statut: {}", status);
        return resultRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    // STATISTIQUES

    /**
     * Statistiques de succès sur une période
     */
    public VerificationStats getStatsBetween(LocalDateTime startDate, LocalDateTime endDate) {
        log.debug("Calcul statistiques entre {} et {}", startDate, endDate);

        long totalVerifications = resultRepository.countTotalVerificationsBetween(startDate, endDate);
        long successfulVerifications = resultRepository.countSuccessfulVerificationsBetween(startDate, endDate);
        Double avgConfidence = resultRepository.averageConfidenceScoreBetween(startDate, endDate).orElse(0.0);

        double successRate = totalVerifications > 0 ?
                (double) successfulVerifications / totalVerifications * 100 : 0.0;

        VerificationStats stats = new VerificationStats(
                totalVerifications,
                successfulVerifications,
                successRate,
                avgConfidence,
                startDate,
                endDate
        );

        log.debug("Statistiques calculées: {}", stats);
        return stats;
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

    // NETTOYAGE ET MAINTENANCE
    /**
     * Nettoie les anciens résultats selon la politique de rétention
     */
    @Transactional
    public int cleanupOldResults(int retentionDays) {
        log.debug("Nettoyage résultats anciens (> {} jours)", retentionDays);

        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
        int deletedCount = resultRepository.deleteByCreatedAtBefore(cutoffDate);

        if (deletedCount > 0) {
            log.info("Nettoyage terminé: {} résultats anciens supprimés (> {} jours)", deletedCount, retentionDays);
        } else {
            log.debug("Aucun résultat ancien à supprimer");
        }

        return deletedCount;
    }

    // MÉTHODES UTILITAIRES

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
    @SuppressWarnings("unchecked")
    public VerificationResultDto convertToDto(VerificationResult result) {
        if (result == null) {
            log.warn("Tentative de conversion d'un résultat null");
            return null;
        }

        try {
            log.debug("Conversion DTO pour RequestId: {}", result.getRequestId());

            VerificationResultDto dto = new VerificationResultDto();

            dto.setRequestId(result.getRequestId());
            dto.setUserIdentifier(result.getUserIdentifier());
            dto.setStatus(result.getStatus().name());
            dto.setMessage(result.getMessage());
            dto.setCreatedAt(result.getCreatedAt());
            dto.setUpdatedAt(result.getUpdatedAt());

            log.debug("Propriétés de base définies pour RequestId: {}", result.getRequestId());

            // Données de comparaison faciale
            if (result.getFaceConfidenceScore() != null) {
                VerificationResultDto.FaceComparisonData faceData = new VerificationResultDto.FaceComparisonData();
                faceData.setConfidenceScore(result.getConfidenceAsDouble());
                faceData.setIsMatch(result.getFaceIsMatch());
                faceData.setMessage(result.getMessage());
                dto.setFaceComparison(faceData);

                log.debug("Données faciales ajoutées - Match: {}, Confiance: {:.3f}",
                        result.getFaceIsMatch(), result.getConfidenceAsDouble());
            }

            // Données d'extraction de document
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
                    @SuppressWarnings("unchecked")
                    Map<String, Object> extractedFields = (Map<String, Object>) extractionData.get("extractedFields");
                    docData.setExtractedFields(extractedFields);
                }

                dto.setDocumentData(docData);

                log.debug("Données document ajoutées - Type: {}, Pays: {}",
                        docData.getDocumentType(), docData.getIssuingCountry());
            }

            log.debug("Conversion DTO terminée avec succès pour RequestId: {}", result.getRequestId());
            return dto;

        } catch (Exception e) {
            log.error("Erreur conversion DTO pour RequestId {}: {}",
                    result.getRequestId(), e.getMessage(), e);
            throw e;
        }
    }

    // CLASSE STATISTIQUES
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