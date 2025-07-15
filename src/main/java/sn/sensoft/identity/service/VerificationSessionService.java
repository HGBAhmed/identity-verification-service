package sn.sensoft.identity.service;

import sn.sensoft.identity.entity.VerificationSession;
import sn.sensoft.identity.repository.VerificationSessionRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Singleton
public class VerificationSessionService {

    private static final Logger log = LoggerFactory.getLogger(VerificationSessionService.class);

    private final VerificationSessionRepository sessionRepository;
    private final ScheduledExecutorService cleanupExecutor;
    private final Random random = new Random();

    // Caractères pour générer les IDs courts (sans confusion 0/O, 1/I)
    private static final String ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public VerificationSessionService(VerificationSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

        log.info("VerificationSessionService initialisé avec nettoyage automatique toutes les 10 minutes");

        // Nettoyage automatique toutes les 10 minutes
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSessions,
                10, 10, TimeUnit.MINUTES);
    }

    // GÉNÉRATION D'ID

    /**
     * Génère un ID court alphanumérique de 8 caractères
     */
    private String generateShortId() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * Génère un ID unique,chek s'il y a similarité en base
     */
    public String generateUniqueSessionId() {
        String id;
        int attempts = 0;
        do {
            id = generateShortId();
            attempts++;

            // Sécurité : max 10 tentatives
            if (attempts > 10) {
                log.error("Impossible de générer un ID unique après 10 tentatives");
                throw new RuntimeException("Impossible de générer un ID unique après 10 tentatives");
            }
        } while (sessionRepository.existsBySessionId(id));

        log.debug("ID unique généré: {} (tentatives: {})", id, attempts);
        return id;
    }

    /**
     * Génère un ID court public pour les requestId
     */
    public String generateShortRequestId() {
        String id = generateShortId();
        log.debug("RequestId généré: {}", id);
        return id;
    }

    // GESTION DES SESSIONS

    /**
     * Crée une nouvelle session de document après extraction réussie
     */
    @Transactional
    public String createDocumentSession(String userIdentifier, String documentType,
                                        String issuingCountry, Map<String, Object> extractedData) {
        String sessionId = generateUniqueSessionId();

        log.debug("Création session document - SessionId: {}, Utilisateur: {}, Type: {}",
                sessionId, userIdentifier, documentType);

        VerificationSession session = new VerificationSession(sessionId, userIdentifier);
        session.setDocumentType(documentType);
        session.setIssuingCountry(issuingCountry);
        session.setExtractedData(extractedData);
        session.setStatus(VerificationSession.SessionStatus.PENDING);

        session = sessionRepository.save(session);

        log.info("Session document créée avec succès - SessionId: {}, Utilisateur: {}, Type: {}",
                sessionId, userIdentifier, documentType);
        return sessionId;
    }

    /**
     * Récupère une session existante et valide
     */
    public VerificationSession getSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            log.warn("Tentative de récupération session avec ID null ou vide");
            return null;
        }

        log.debug("Récupération session: {}", sessionId);

        VerificationSession session = sessionRepository.findBySessionId(sessionId).orElse(null);

        if (session == null) {
            log.debug("Session non trouvée: {}", sessionId);
            return null;
        }

        if (session.isExpired()) {
            log.warn("Session expirée: {} (expirée à: {})", sessionId, session.getExpiresAt());
            sessionRepository.deleteById(session.getId());
            return null;
        }

        if (!session.isValid()) {
            log.warn("Session invalide: {} (statut: {})", sessionId, session.getStatus());
            sessionRepository.deleteById(session.getId());
            return null;
        }

        log.debug("Session récupérée avec succès: {} (expire à: {})", sessionId, session.getExpiresAt());
        return session;
    }

    /**
     * Met à jour le statut d'une session
     */
    @Transactional
    public void updateSessionStatus(String sessionId, VerificationSession.SessionStatus status) {
        log.debug("Mise à jour statut session: {} -> {}", sessionId, status);

        VerificationSession session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session != null) {
            session.setStatus(status);
            sessionRepository.update(session);
            log.info("Session {} mise à jour avec statut: {}", sessionId, status);
        } else {
            log.warn("Session non trouvée pour mise à jour statut: {}", sessionId);
        }
    }

    /**
     * Prolonge l'expiration d'une session
     */
    @Transactional
    public void extendSession(String sessionId, int additionalMinutes) {
        log.debug("Prolongation session: {} de {} minutes", sessionId, additionalMinutes);

        VerificationSession session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session != null) {
            LocalDateTime oldExpiration = session.getExpiresAt();
            session.extendExpiration(additionalMinutes);
            sessionRepository.update(session);
            log.info("Session {} prolongée de {} minutes (nouvelle expiration: {})",
                    sessionId, additionalMinutes, session.getExpiresAt());
        } else {
            log.warn("Session non trouvée pour prolongation: {}", sessionId);
        }
    }

    /**
     * Supprime une session après utilisation
     */
    @Transactional
    public void removeSession(String sessionId) {
        if (sessionId != null) {
            log.debug("Suppression session: {}", sessionId);

            VerificationSession session = sessionRepository.findBySessionId(sessionId).orElse(null);
            if (session != null) {
                sessionRepository.deleteById(session.getId());
                log.info("Session supprimée avec succès: {}", sessionId);
            } else {
                log.debug("Session déjà supprimée ou inexistante: {}", sessionId);
            }
        }
    }

    /**
     * Supprime une session par ID complet
     */
    @Transactional
    public void removeSessionById(java.util.UUID sessionUuid) {
        log.debug("Suppression session par UUID: {}", sessionUuid);
        sessionRepository.deleteById(sessionUuid);
    }

    /**
     * Crée une session temporaire VIDE (avant extraction)
     */
    @Transactional
    public String createTemporarySession(String userIdentifier) {
        String sessionId = generateUniqueSessionId();

        log.debug("Création session temporaire - SessionId: {}, Utilisateur: {}", sessionId, userIdentifier);

        VerificationSession session = new VerificationSession(sessionId, userIdentifier);
        session.setStatus(VerificationSession.SessionStatus.PENDING);
        // Les autres champs (documentType, issuingCountry, extractedData) seront mis à jour plus tard

        session = sessionRepository.save(session);

        log.info("Session temporaire créée avec succès - SessionId: {}, Utilisateur: {}", sessionId, userIdentifier);
        return sessionId;
    }

    /**
     * Met à jour une session avec les données d'extraction
     */
    @Transactional
    public void updateSessionWithExtractionData(String sessionId, String documentType,
                                                String issuingCountry, Map<String, Object> extractedData) {
        log.debug("Mise à jour session avec données extraction - SessionId: {}, Type: {}", sessionId, documentType);

        VerificationSession session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session != null) {
            session.setDocumentType(documentType);
            session.setIssuingCountry(issuingCountry);
            session.setExtractedData(extractedData);
            session.setStatus(VerificationSession.SessionStatus.PROCESSING);

            sessionRepository.update(session);
            log.info("Session {} mise à jour avec données extraction - Type: {}, Pays: {}",
                    sessionId, documentType, issuingCountry);
        } else {
            log.warn("Session non trouvée pour mise à jour extraction: {}", sessionId);
        }
    }

    /**
     * Marque une session comme terminée
     */
    @Transactional
    public void markSessionAsCompleted(String sessionId) {
        log.debug("Marquage session comme terminée: {}", sessionId);
        updateSessionStatus(sessionId, VerificationSession.SessionStatus.COMPLETED);
    }

    // NETTOYAGE AUTOMATIQUE

    /**
     * Nettoyage automatique des sessions expirées
     */
    @Transactional
    public void cleanupExpiredSessions() {
        log.debug("Démarrage nettoyage sessions expirées");

        LocalDateTime now = LocalDateTime.now();
        int removedCount = sessionRepository.deleteByExpiresAtBefore(now);

        if (removedCount > 0) {
            log.info("Nettoyage terminé: {} sessions expirées supprimées", removedCount);
        } else {
            log.debug("Aucune session expirée à supprimer");
        }
    }

    // REQUÊTES ET STATISTIQUES

    /**
     * Statistiques pour debug
     */
    public int getActiveSessionsCount() {
        cleanupExpiredSessions(); // Nettoyage avant comptage
        int count = (int) sessionRepository.findByStatusAndExpiresAtAfter(
                VerificationSession.SessionStatus.PENDING,
                LocalDateTime.now()
        ).size();

        log.debug("Nombre de sessions actives: {}", count);
        return count;
    }

    /**
     * Sessions actives d'un utilisateur
     */
    public java.util.List<VerificationSession> getUserActiveSessions(String userIdentifier) {
        log.debug("Recherche sessions actives pour utilisateur: {}", userIdentifier);
        return sessionRepository.findByUserIdentifierAndExpiresAtAfter(userIdentifier, LocalDateTime.now());
    }

    /**
     * Statistiques sur une période
     */
    public long getSessionCountBetween(LocalDateTime startDate, LocalDateTime endDate) {
        log.debug("Comptage sessions entre {} et {}", startDate, endDate);
        return sessionRepository.countByCreatedAtBetween(startDate, endDate);
    }

    /**
     * Sessions par statut
     */
    public java.util.List<VerificationSession> getSessionsByStatus(VerificationSession.SessionStatus status) {
        log.debug("Recherche sessions par statut: {}", status);
        return sessionRepository.findByStatusAndExpiresAtAfter(status, LocalDateTime.now());
    }

    // MÉTHODES UTILITAIRES

    /**
     * Convertit DocumentSession vers l entité
     */
    public VerificationSession convertFromDocumentSession(sn.sensoft.identity.dto.DocumentSession oldSession) {
        if (oldSession == null) return null;

        log.debug("Conversion DocumentSession vers VerificationSession: {}", oldSession.getDocumentId());

        VerificationSession newSession = new VerificationSession(
                oldSession.getDocumentId(),
                oldSession.getUserIdentifier()
        );
        newSession.setDocumentType(oldSession.getDocumentType());
        newSession.setIssuingCountry(oldSession.getIssuingCountry());
        newSession.setExtractedData(oldSession.getExtractedData());
        newSession.setCreatedAt(oldSession.getCreatedAt());
        newSession.setExpiresAt(oldSession.getExpiresAt());

        return newSession;
    }

    /**
     * Convertit l entité vers le DTO
     */
    public sn.sensoft.identity.dto.DocumentSession convertToDocumentSession(VerificationSession session) {
        if (session == null) return null;

        log.debug("Conversion VerificationSession vers DocumentSession: {}", session.getSessionId());

        sn.sensoft.identity.dto.DocumentSession oldSession = new sn.sensoft.identity.dto.DocumentSession(
                session.getSessionId(),
                session.getUserIdentifier(),
                null, // documentPath sera récupéré via FileStorageService
                session.getDocumentType(),
                session.getIssuingCountry(),
                session.getExtractedData()
        );
        oldSession.setCreatedAt(session.getCreatedAt());
        oldSession.setExpiresAt(session.getExpiresAt());

        return oldSession;
    }

    // SHUTDOWN PROPRE

    /**
     * Shutdown propre du service
     */
    public void shutdown() {
        log.info("Arrêt du service de sessions");

        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Arrêt forcé du service de nettoyage");
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.error("Interruption lors de l'arrêt du service");
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("Service de sessions arrêté");
    }
}