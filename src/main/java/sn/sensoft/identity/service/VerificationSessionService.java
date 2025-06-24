package sn.sensoft.identity.service;

import sn.sensoft.identity.dto.DocumentSession;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.UUID;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;

@Singleton
public class VerificationSessionService {

    private final Map<String, DocumentSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;
    private final Random random = new Random();

    // Caractères pour générer les IDs courts (sans confusion 0/O, 1/I)
    private static final String ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public VerificationSessionService() {
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

        // Nettoyage automatique toutes les 10 minutes
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSessions,
                10, 10, TimeUnit.MINUTES);
    }

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
     * Génère un ID unique (vérifie les collisions)
     */
    private String generateUniqueId() {
        String id;
        int attempts = 0;
        do {
            id = generateShortId();
            attempts++;

            // Sécurité : max 10 tentatives (collision très improbable)
            if (attempts > 10) {
                throw new RuntimeException("Impossible de générer un ID unique après 10 tentatives");
            }
        } while (sessions.containsKey(id));

        return id;
    }

    /**
     * Génère un ID court public pour les requestId
     */
    public String generateShortRequestId() {
        return generateShortId();
    }

    /**
     * Crée une nouvelle session de document après extraction réussie
     */
    public String createDocumentSession(String userIdentifier, String documentPath,
                                        String documentType, String issuingCountry,
                                        Map<String, Object> extractedData) {
        String documentId = generateUniqueId();

        DocumentSession session = new DocumentSession(
                documentId, userIdentifier, documentPath,
                documentType, issuingCountry, extractedData
        );

        sessions.put(documentId, session);

        System.out.println(" Session créée: " + documentId + " pour utilisateur: " + userIdentifier);
        return documentId;
    }

    /**
     * Récupère une session existante et valide
     */
    public DocumentSession getSession(String documentId) {
        if (documentId == null || documentId.trim().isEmpty()) {
            return null;
        }

        DocumentSession session = sessions.get(documentId);

        if (session == null) {
            System.out.println(" Session non trouvée: " + documentId);
            return null;
        }

        if (session.isExpired()) {
            System.out.println(" Session expirée: " + documentId);
            sessions.remove(documentId);
            return null;
        }

        if (!session.isValid()) {
            System.out.println(" Session invalide: " + documentId);
            sessions.remove(documentId);
            return null;
        }

        System.out.println(" Session récupérée: " + documentId);
        return session;
    }

    /**
     * Supprime une session après utilisation
     */
    public void removeSession(String documentId) {
        if (documentId != null) {
            DocumentSession removed = sessions.remove(documentId);
            if (removed != null) {
                System.out.println(" Session supprimée: " + documentId);
            }
        }
    }

    /**
     * Nettoyage automatique des sessions expirées
     */
    private void cleanupExpiredSessions() {
        LocalDateTime now = LocalDateTime.now();
        int removedCount = 0;

        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
                removedCount++;
            }
        }

        if (removedCount > 0) {
            System.out.println(" Nettoyage: " + removedCount + " sessions expirées supprimées");
        }
    }

    /**
     * Statistiques pour debug
     */
    public int getActiveSessionsCount() {
        cleanupExpiredSessions(); // Nettoyage avant comptage
        return sessions.size();
    }

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