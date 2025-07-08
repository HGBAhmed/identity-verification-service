package sn.sensoft.identity.service;

import sn.sensoft.identity.entity.VerificationSession;
import sn.sensoft.identity.repository.VerificationSessionRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Singleton
public class VerificationSessionService {

    private final VerificationSessionRepository sessionRepository;
    private final ScheduledExecutorService cleanupExecutor;
    private final Random random = new Random();

    // Caractères pour générer les IDs courts (sans confusion 0/O, 1/I)
    private static final String ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public VerificationSessionService(VerificationSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

        // Nettoyage automatique toutes les 10 minutes
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSessions,
                10, 10, TimeUnit.MINUTES);
    }

    // ===================================
    // GÉNÉRATION D'IDS COURTS
    // ===================================

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
     * Génère un ID unique (vérifie les collisions en base)
     */
    public String generateUniqueSessionId() {
        String id;
        int attempts = 0;
        do {
            id = generateShortId();
            attempts++;

            // Sécurité : max 10 tentatives (collision très improbable)
            if (attempts > 10) {
                throw new RuntimeException("Impossible de générer un ID unique après 10 tentatives");
            }
        } while (sessionRepository.existsBySessionId(id));

        return id;
    }

    /**
     * Génère un ID court public pour les requestId (pas besoin d'unicité stricte)
     */
    public String generateShortRequestId() {
        return generateShortId();
    }

    // ===================================
    // GESTION DES SESSIONS
    // ===================================

    /**
     * Crée une nouvelle session de document après extraction réussie
     */
    @Transactional
    public String createDocumentSession(String userIdentifier, String documentType,
                                        String issuingCountry, Map<String, Object> extractedData) {
        String sessionId = generateUniqueSessionId();

        VerificationSession session = new VerificationSession(sessionId, userIdentifier);
        session.setDocumentType(documentType);
        session.setIssuingCountry(issuingCountry);
        session.setExtractedData(extractedData);
        session.setStatus(VerificationSession.SessionStatus.PENDING);

        session = sessionRepository.save(session);

        System.out.println(" Session créée: " + sessionId + " pour utilisateur: " + userIdentifier);
        return sessionId;
    }

    /**
     * Récupère une session existante et valide
     */
    public VerificationSession getSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }

        VerificationSession session = sessionRepository.findBySessionId(sessionId).orElse(null);

        if (session == null) {
            System.out.println(" Session non trouvée: " + sessionId);
            return null;
        }

        if (session.isExpired()) {
            System.out.println(" Session expirée: " + sessionId);
            sessionRepository.deleteById(session.getId());
            return null;
        }

        if (!session.isValid()) {
            System.out.println(" Session invalide: " + sessionId);
            sessionRepository.deleteById(session.getId());
            return null;
        }

        System.out.println(" Session récupérée: " + sessionId);
        return session;
    }

    /**
     * Met à jour le statut d'une session
     */
    @Transactional
    public void updateSessionStatus(String sessionId, VerificationSession.SessionStatus status) {
        VerificationSession session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session != null) {
            session.setStatus(status);
            sessionRepository.update(session);
            System.out.println(" Session " + sessionId + " mise à jour: " + status);
        }
    }

    /**
     * Prolonge l'expiration d'une session
     */
    @Transactional
    public void extendSession(String sessionId, int additionalMinutes) {
        VerificationSession session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session != null) {
            session.extendExpiration(additionalMinutes);
            sessionRepository.update(session);
            System.out.println(" Session " + sessionId + " prolongée de " + additionalMinutes + " minutes");
        }
    }

    /**
     * Supprime une session après utilisation
     */
    @Transactional
    public void removeSession(String sessionId) {
        if (sessionId != null) {
            VerificationSession session = sessionRepository.findBySessionId(sessionId).orElse(null);
            if (session != null) {
                sessionRepository.deleteById(session.getId());
                System.out.println(" Session supprimée: " + sessionId);
            }
        }
    }

    /**
     * Supprime une session par ID complet
     */
    @Transactional
    public void removeSessionById(java.util.UUID sessionUuid) {
        sessionRepository.deleteById(sessionUuid);
    }


    /**
     * Crée une session temporaire VIDE (avant extraction)
     */
    @Transactional
    public String createTemporarySession(String userIdentifier) {
        String sessionId = generateUniqueSessionId();

        VerificationSession session = new VerificationSession(sessionId, userIdentifier);
        session.setStatus(VerificationSession.SessionStatus.PENDING);
        // Les autres champs (documentType, issuingCountry, extractedData) seront mis à jour plus tard

        session = sessionRepository.save(session);

        System.out.println("Session temporaire créée: " + sessionId + " pour utilisateur: " + userIdentifier);
        return sessionId;
    }

    /**
     * Met à jour une session avec les données d'extraction
     */
    @Transactional
    public void updateSessionWithExtractionData(String sessionId, String documentType,
                                                String issuingCountry, Map<String, Object> extractedData) {
        VerificationSession session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session != null) {
            session.setDocumentType(documentType);
            session.setIssuingCountry(issuingCountry);
            session.setExtractedData(extractedData);
            session.setStatus(VerificationSession.SessionStatus.PROCESSING);

            sessionRepository.update(session);
            System.out.println("Session " + sessionId + " mise à jour avec données d'extraction");
        } else {
            System.out.println("Session non trouvée pour mise à jour: " + sessionId);
        }
    }

    /**
     * Marque une session comme terminée
     */
    @Transactional
    public void markSessionAsCompleted(String sessionId) {
        updateSessionStatus(sessionId, VerificationSession.SessionStatus.COMPLETED);
        System.out.println(" Session " + sessionId + " marquée comme COMPLETED");
    }

    // ===================================
    // NETTOYAGE AUTOMATIQUE
    // ===================================

    /**
     * Nettoyage automatique des sessions expirées
     */
    @Transactional
    public void cleanupExpiredSessions() {
        LocalDateTime now = LocalDateTime.now();
        int removedCount = sessionRepository.deleteByExpiresAtBefore(now);

        if (removedCount > 0) {
            System.out.println(" Nettoyage: " + removedCount + " sessions expirées supprimées");
        }
    }

    // ===================================
    // REQUÊTES ET STATISTIQUES
    // ===================================

    /**
     * Statistiques pour debug
     */
    public int getActiveSessionsCount() {
        cleanupExpiredSessions(); // Nettoyage avant comptage
        return (int) sessionRepository.findByStatusAndExpiresAtAfter(
                VerificationSession.SessionStatus.PENDING,
                LocalDateTime.now()
        ).size();
    }

    /**
     * Sessions actives d'un utilisateur
     */
    public java.util.List<VerificationSession> getUserActiveSessions(String userIdentifier) {
        return sessionRepository.findByUserIdentifierAndExpiresAtAfter(userIdentifier, LocalDateTime.now());
    }

    /**
     * Statistiques sur une période
     */
    public long getSessionCountBetween(LocalDateTime startDate, LocalDateTime endDate) {
        return sessionRepository.countByCreatedAtBetween(startDate, endDate);
    }

    /**
     * Sessions par statut
     */
    public java.util.List<VerificationSession> getSessionsByStatus(VerificationSession.SessionStatus status) {
        return sessionRepository.findByStatusAndExpiresAtAfter(status, LocalDateTime.now());
    }

    // ===================================
    // MÉTHODES UTILITAIRES
    // ===================================

    /**
     * Convertit l'ancienne DocumentSession vers la nouvelle entité
     */
    public VerificationSession convertFromDocumentSession(sn.sensoft.identity.dto.DocumentSession oldSession) {
        if (oldSession == null) return null;

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
     * Convertit la nouvelle entité vers l'ancien DTO (pour compatibilité)
     */
    public sn.sensoft.identity.dto.DocumentSession convertToDocumentSession(VerificationSession session) {
        if (session == null) return null;

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

    // ===================================
    // SHUTDOWN PROPRE
    // ===================================

    /**
     * Shutdown propre du service
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}