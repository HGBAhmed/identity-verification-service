package sn.sensoft.identity.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.micronaut.serde.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Singleton
public class FaceComparisonService {

    private static final Logger log = LoggerFactory.getLogger(FaceComparisonService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String aiServerBaseUrl;
    private final Duration timeout;
    private final double defaultThreshold;

    @Inject
    public FaceComparisonService(@Client("ai-server") HttpClient httpClient,
                                 ObjectMapper objectMapper,
                                 @Value("${app.ai-server.base-url:http://localhost:5000}") String aiServerBaseUrl,
                                 @Value("${app.ai-server.timeout:60s}") Duration timeout,
                                 @Value("${app.face-comparison.default-threshold:0.68}") double defaultThreshold) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.aiServerBaseUrl = aiServerBaseUrl;
        this.timeout = timeout;
        this.defaultThreshold = defaultThreshold;

        log.info("FaceComparisonService initialisé avec serveur IA: {} (seuil: {}, timeout: {})",
                aiServerBaseUrl, defaultThreshold, timeout);
    }

    /**
     * Compare deux images avec seuil donné via serveur HTTP
     */
    public FaceComparisonResult compareImages(String imagePath1, String imagePath2, Double customThreshold)
            throws IOException {

        double thresholdToUse = customThreshold != null ? customThreshold : defaultThreshold;

        log.debug("Début comparaison faciale via HTTP - Image1: {}, Image2: {}, Seuil: {}",
                imagePath1, imagePath2, thresholdToUse);

        try {
            // Préparer la requête
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("img1_path", imagePath1);
            requestBody.put("img2_path", imagePath2);
            requestBody.put("threshold", thresholdToUse);

            HttpRequest<Map<String, Object>> request = HttpRequest.POST(aiServerBaseUrl + "/compare/faces", requestBody)
                    .header("Content-Type", MediaType.APPLICATION_JSON)
                    .header("Accept", MediaType.APPLICATION_JSON);

            log.debug("Envoi requête comparaison vers: {}", aiServerBaseUrl + "/compare/faces");

            // Exécuter la requête
            HttpResponse<String> response = httpClient.toBlocking()
                    .exchange(request, String.class);

            log.debug("Réponse comparaison reçue - Status: {}", response.getStatus());

            if (response.getStatus().getCode() != 200) {
                String errorMsg = String.format("Erreur serveur IA comparaison - Status: %d, Body: %s",
                        response.getStatus().getCode(), response.body());
                log.error(errorMsg);
                throw new IOException(errorMsg);
            }

            // Parser la réponse
            FaceComparisonResult result = parseComparisonResult(response.body());

            log.info("Comparaison faciale terminée via HTTP - Vérifié: {}, Confiance: {:.3f}, Seuil: {:.3f}, Personnalisé: {}",
                    result.isVerified(), result.getConfidence(), result.getThreshold(), result.isCustomThresholdUsed());

            return result;

        } catch (Exception e) {
            log.error("Erreur comparaison faciale via HTTP - Image1: {}, Image2: {}: {}",
                    imagePath1, imagePath2, e.getMessage(), e);
            throw new IOException("Erreur comparaison HTTP: " + e.getMessage(), e);
        }
    }

    /**
     * Compare deux images avec seuil par défaut
     */
    public FaceComparisonResult compareImages(String imagePath1, String imagePath2) throws IOException {
        return compareImages(imagePath1, imagePath2, null);
    }

    /**
     * Teste la disponibilité du service de comparaison faciale
     */
    public boolean isFaceComparisonServiceAvailable() {
        try {
            // Utiliser l'endpoint de santé pour vérifier que DeepFace est prêt
            HttpRequest<Object> request = HttpRequest.GET(aiServerBaseUrl + "/health")
                    .header("Accept", MediaType.APPLICATION_JSON);

            HttpResponse<String> response = httpClient.toBlocking()
                    .exchange(request, String.class);

            if (response.getStatus().getCode() == 200) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> healthData = objectMapper.readValue(response.body(), Map.class);
                    return Boolean.TRUE.equals(healthData.get("deepface_ready"));
                } catch (Exception e) {
                    log.debug("Erreur parsing santé DeepFace: {}", e.getMessage());
                    return false;
                }
            }

            return false;

        } catch (Exception e) {
            log.debug("Erreur vérification service comparaison faciale: {}", e.getMessage());
            return false;
        }
    }

    /**
     * pour les métriques de performance du service
     */
    public Map<String, Object> getPerformanceMetrics() {
        try {
            HttpRequest<Object> request = HttpRequest.GET(aiServerBaseUrl + "/status")
                    .header("Accept", MediaType.APPLICATION_JSON);

            HttpResponse<String> response = httpClient.toBlocking()
                    .exchange(request, String.class);

            if (response.getStatus().getCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> status = objectMapper.readValue(response.body(), Map.class);

                // Extraire les métriques pertinentes
                Map<String, Object> metrics = new HashMap<>();
                metrics.put("server_status", status.get("status"));
                metrics.put("uptime", status.get("uptime"));

                @SuppressWarnings("unchecked")
                Map<String, Object> models = (Map<String, Object>) status.get("models");
                if (models != null) {
                    metrics.put("deepface_ready", models.get("deepface_ready"));
                    metrics.put("models_loaded", models.get("loaded"));
                }

                return metrics;
            }

        } catch (Exception e) {
            log.debug("Erreur récupération métriques: {}", e.getMessage());
        }

        return Map.of("status", "unavailable");
    }

    /**
     * Parse le résultat JSON de la comparaison faciale
     */
    private FaceComparisonResult parseComparisonResult(String jsonResponse) throws IOException {
        try {
            log.debug("Parsing résultat comparaison faciale");

            @SuppressWarnings("unchecked")
            Map<String, Object> jsonNode = objectMapper.readValue(jsonResponse, Map.class);

            FaceComparisonResult result = new FaceComparisonResult();

            // Gestion des erreurs
            if ("error".equals(jsonNode.get("status")) || jsonNode.containsKey("error")) {
                result.setVerified(false);
                result.setConfidence(0.0);
                result.setStatus("error");
                result.setError((String) jsonNode.get("error"));

                // Log traceback si disponible pour debug
                if (jsonNode.containsKey("traceback")) {
                    log.debug("Traceback serveur IA comparaison: {}", jsonNode.get("traceback"));
                }

                log.warn("Erreur comparaison faciale serveur IA: {}", result.getError());
                return result;
            }

            // Résultat de succès
            result.setVerified((Boolean) jsonNode.get("verified"));
            result.setConfidence(((Number) jsonNode.get("confidence")).doubleValue());
            result.setStatus((String) jsonNode.get("status"));

            if (jsonNode.containsKey("threshold")) {
                result.setThreshold(((Number) jsonNode.get("threshold")).doubleValue());
            }
            if (jsonNode.containsKey("default_threshold")) {
                result.setDefaultThreshold(((Number) jsonNode.get("default_threshold")).doubleValue());
            }
            if (jsonNode.containsKey("custom_threshold_used")) {
                result.setCustomThresholdUsed((Boolean) jsonNode.get("custom_threshold_used"));
            }

            log.debug("Comparaison faciale parsée - Vérifié: {}, Confiance: {:.3f}, Seuil: {:.3f}",
                    result.isVerified(), result.getConfidence(), result.getThreshold());

            return result;

        } catch (Exception e) {
            log.error("Erreur parsing résultat comparaison faciale: {}", jsonResponse, e);
            throw new IOException("Erreur parsing réponse serveur IA comparaison: " + jsonResponse, e);
        }
    }

    /**
     * Classe de résultat
     */
    public static class FaceComparisonResult {
        private boolean verified;
        private double confidence;
        private double threshold;
        private Double defaultThreshold;
        private boolean customThresholdUsed;
        private String status;
        private String error;

        public boolean isVerified() { return verified; }
        public void setVerified(boolean verified) { this.verified = verified; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }

        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }

        public Double getDefaultThreshold() { return defaultThreshold; }
        public void setDefaultThreshold(Double defaultThreshold) { this.defaultThreshold = defaultThreshold; }

        public boolean isCustomThresholdUsed() { return customThresholdUsed; }
        public void setCustomThresholdUsed(boolean customThresholdUsed) { this.customThresholdUsed = customThresholdUsed; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}