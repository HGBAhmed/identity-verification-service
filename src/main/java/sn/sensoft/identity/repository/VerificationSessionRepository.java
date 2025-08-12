package sn.sensoft.identity.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.jpa.JpaSpecificationExecutor;
import sn.sensoft.identity.entity.VerificationSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationSessionRepository extends JpaRepository<VerificationSession, UUID> {

    // Recherche par session ID (clé métier)
    Optional<VerificationSession> findBySessionId(String sessionId);

    // Recherche par utilisateur
    List<VerificationSession> findByUserIdentifierAndExpiresAtAfter(String userIdentifier, LocalDateTime now);

    // Sessions expirées à nettoyer
    List<VerificationSession> findByExpiresAtBefore(LocalDateTime now);

    // Supprimer les sessions expirées
    int deleteByExpiresAtBefore(LocalDateTime now);

    // Sessions actives par statut
    List<VerificationSession> findByStatusAndExpiresAtAfter(VerificationSession.SessionStatus status, LocalDateTime now);

    // Statistiques
    long countByCreatedAtBetween(LocalDateTime startDate, LocalDateTime endDate);

    // Vérifier existence
    boolean existsBySessionId(String sessionId);

    // à ajouter parce que apparemment pas pris en compte par micronaut
    @Override
    <S extends VerificationSession> S update(S session);
}