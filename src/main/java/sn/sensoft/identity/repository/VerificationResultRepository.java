package sn.sensoft.identity.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import sn.sensoft.identity.entity.VerificationResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationResultRepository extends JpaRepository<VerificationResult, UUID> {

    // Recherche par request ID
    Optional<VerificationResult> findByRequestId(String requestId);

    // Recherche par utilisateur
    List<VerificationResult> findByUserIdentifierOrderByCreatedAtDesc(String userIdentifier);

    // Recherche par session
    Optional<VerificationResult> findBySessionId(String sessionId);

    // Historique des vérifications
    List<VerificationResult> findByUserIdentifierAndCreatedAtBetween(
            String userIdentifier, LocalDateTime startDate, LocalDateTime endDate);

    // Statistiques de succès
    @Query("SELECT COUNT(v) FROM VerificationResult v WHERE v.faceIsMatch = true AND v.createdAt BETWEEN :startDate AND :endDate")
    long countSuccessfulVerificationsBetween(LocalDateTime startDate, LocalDateTime endDate);

    @Query("SELECT COUNT(v) FROM VerificationResult v WHERE v.createdAt BETWEEN :startDate AND :endDate")
    long countTotalVerificationsBetween(LocalDateTime startDate, LocalDateTime endDate);

    // Confiance moyenne
    @Query("SELECT AVG(v.faceConfidenceScore) FROM VerificationResult v WHERE v.faceIsMatch = true AND v.createdAt BETWEEN :startDate AND :endDate")
    Optional<Double> averageConfidenceScoreBetween(LocalDateTime startDate, LocalDateTime endDate);

    // Résultats par statut
    List<VerificationResult> findByStatusOrderByCreatedAtDesc(VerificationResult.VerificationStatus status);

    // Vérifications récentes
    @Query("SELECT v FROM VerificationResult v ORDER BY v.createdAt DESC LIMIT 10")
    List<VerificationResult> findTop10ByOrderByCreatedAtDesc();

    // Nettoyage des anciens résultats
    int deleteByCreatedAtBefore(LocalDateTime cutoffDate);

    //parce que micronaut ne le fait pas auto
    @Override
    VerificationResult update(VerificationResult result);

    // Verifier si un utilisateur a deja fait des verifications
    boolean existsByUserIdentifier(String userIdentifier);
}