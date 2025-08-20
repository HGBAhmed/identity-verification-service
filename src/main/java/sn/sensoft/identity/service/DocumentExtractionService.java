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
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Singleton
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String aiServerBaseUrl;
    private final Duration timeout;

    @Inject
    public DocumentExtractionService(@Client("ai-server") HttpClient httpClient,
                                     ObjectMapper objectMapper,
                                     @Value("${app.ai-server.base-url:http://localhost:5000}") String aiServerBaseUrl,
                                     @Value("${app.ai-server.timeout:60s}") Duration timeout) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.aiServerBaseUrl = aiServerBaseUrl;
        this.timeout = timeout;

        log.info("DocumentExtractionService initialisé avec serveur IA: {} (timeout: {})",
                aiServerBaseUrl, timeout);

        // Test de connexion au démarrage
        testAIServerConnection();
    }

    /**
     * Test de connexion au serveur IA au démarrage
     */
    private void testAIServerConnection() {
        try {
            log.debug("Test connexion serveur IA...");

            HttpRequest<Object> request = HttpRequest.GET(aiServerBaseUrl + "/health")
                    .header("Accept", MediaType.APPLICATION_JSON);

            HttpResponse<String> response = httpClient.toBlocking()
                    .exchange(request, String.class);

            if (response.getStatus().getCode() == 200) {
                log.info("Connexion serveur IA réussie");

                // Log des détails si disponibles
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> healthData = objectMapper.readValue(response.body(), Map.class);
                    log.info("Serveur IA - Modèles chargés: {}, DeepFace: {}, EasyOCR: {}",
                            healthData.get("models_loaded"),
                            healthData.get("deepface_ready"),
                            healthData.get("easyocr_ready"));
                } catch (Exception e) {
                    log.debug("Impossible de parser les détails de santé: {}", e.getMessage());
                }
            } else {
                log.warn("Serveur IA répond mais status: {}", response.getStatus());
            }
        } catch (Exception e) {
            log.error("Erreur connexion serveur IA: {}", e.getMessage());
            log.warn("Le serveur IA n'est peut-être pas encore démarré. Vérifiez qu'il tourne sur: {}", aiServerBaseUrl);
        }
    }

    /**
     * Extraction d'un document via serveur HTTP
     */
    public DocumentExtractionResult extractDocumentData(String imagePath) throws IOException {
        return extractDocumentData(imagePath, null);
    }

    /**
     * Extraction d'un côté spécifié via serveur HTTP
     */
    public DocumentExtractionResult extractDocumentData(String imagePath, String expectedSide) throws IOException {
        log.debug("Début extraction document via HTTP - Image: {}, Côté: {}", imagePath, expectedSide);

        try {
            // Préparer la requête
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("image_path", imagePath);
            if (expectedSide != null) {
                requestBody.put("expected_side", expectedSide);
            }

            HttpRequest<Map<String, Object>> request = HttpRequest.POST(aiServerBaseUrl + "/extract/document", requestBody)
                    .header("Content-Type", MediaType.APPLICATION_JSON)
                    .header("Accept", MediaType.APPLICATION_JSON);

            log.debug("Envoi requête extraction vers: {}", aiServerBaseUrl + "/extract/document");

            // Exécuter la requête avec timeout
            HttpResponse<String> response = httpClient.toBlocking()
                    .exchange(request, String.class);

            log.debug("Réponse extraction reçue - Status: {}", response.getStatus());

            if (response.getStatus().getCode() != 200) {
                String errorMsg = String.format("Erreur serveur IA - Status: %d, Body: %s",
                        response.getStatus().getCode(), response.body());
                log.error(errorMsg);
                throw new IOException(errorMsg);
            }

            // Parser la réponse
            DocumentExtractionResult result = parseExtractionResult(response.body());

            if (result.isSuccessful()) {
                log.info("Extraction document réussie via HTTP - Type: {}, Pays: {}",
                        result.getDocumentType(), result.getIssuingCountry());
            } else {
                log.warn("Extraction document échouée via HTTP: {}", result.getError());
            }

            return result;

        } catch (Exception e) {
            log.error("Erreur extraction document via HTTP pour image {}: {}", imagePath, e.getMessage(), e);
            throw new IOException("Erreur extraction HTTP: " + e.getMessage(), e);
        }
    }

    /**
     * Extraction recto/verso pour cartes d'identité via serveur HTTP
     */
    public DocumentExtractionResult extractRectoVersoData(String rectoPath, String versoPath) throws IOException {
        log.debug("Début extraction recto/verso via HTTP - Recto: {}, Verso: {}", rectoPath, versoPath);

        try {
            // Préparer la requête
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("recto_path", rectoPath);
            requestBody.put("verso_path", versoPath);

            HttpRequest<Map<String, Object>> request = HttpRequest.POST(aiServerBaseUrl + "/extract/recto-verso", requestBody)
                    .header("Content-Type", MediaType.APPLICATION_JSON)
                    .header("Accept", MediaType.APPLICATION_JSON);

            log.debug("Envoi requête recto/verso vers: {}", aiServerBaseUrl + "/extract/recto-verso");

            // Exécuter la requête
            HttpResponse<String> response = httpClient.toBlocking()
                    .exchange(request, String.class);

            log.debug("Réponse recto/verso reçue - Status: {}", response.getStatus());

            if (response.getStatus().getCode() != 200) {
                String errorMsg = String.format("Erreur serveur IA recto/verso - Status: %d, Body: %s",
                        response.getStatus().getCode(), response.body());
                log.error(errorMsg);
                throw new IOException(errorMsg);
            }

            // Parser la réponse
            DocumentExtractionResult result = parseExtractionResult(response.body());

            if (result.isSuccessful()) {
                log.info("Extraction recto/verso réussie via HTTP - Type: {}, Pays: {}",
                        result.getDocumentType(), result.getIssuingCountry());
            } else {
                log.warn("Extraction recto/verso échouée via HTTP: {}", result.getError());
            }

            return result;

        } catch (Exception e) {
            log.error("Erreur extraction recto/verso via HTTP - Recto: {}, Verso: {}: {}",
                    rectoPath, versoPath, e.getMessage(), e);
            throw new IOException("Erreur extraction recto/verso HTTP: " + e.getMessage(), e);
        }
    }

    /**
     * Vérification de l'etat du serveur IA
     */
    public boolean isAIServerHealthy() {
        try {
            HttpRequest<Object> request = HttpRequest.GET(aiServerBaseUrl + "/health")
                    .header("Accept", MediaType.APPLICATION_JSON);

            HttpResponse<String> response = httpClient.toBlocking()
                    .exchange(request, String.class);

            boolean healthy = response.getStatus().getCode() == 200;

            if (healthy) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> healthData = objectMapper.readValue(response.body(), Map.class);
                    healthy = "healthy".equals(healthData.get("status")) &&
                            Boolean.TRUE.equals(healthData.get("models_loaded"));
                } catch (Exception e) {
                    log.debug("Erreur parsing santé: {}", e.getMessage());
                }
            }

            return healthy;

        } catch (Exception e) {
            log.debug("Erreur vérification santé serveur IA: {}", e.getMessage());
            return false;
        }
    }

    /**
     * pour avoir le statut détaillé du serveur IA
     */
    public Map<String, Object> getAIServerStatus() {
        try {
            HttpRequest<Object> request = HttpRequest.GET(aiServerBaseUrl + "/status")
                    .header("Accept", MediaType.APPLICATION_JSON);

            HttpResponse<String> response = httpClient.toBlocking()
                    .exchange(request, String.class);

            if (response.getStatus().getCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> status = objectMapper.readValue(response.body(), Map.class);
                return status;
            }

        } catch (Exception e) {
            log.debug("Erreur récupération statut serveur IA: {}", e.getMessage());
        }

        return Map.of("status", "unavailable", "error", "Serveur IA non accessible");
    }

    /**
     * Parse le résultat JSON de l'extraction
     */
    private DocumentExtractionResult parseExtractionResult(String jsonResponse) throws IOException {
        try {
            log.debug("Parsing réponse extraction document");

            @SuppressWarnings("unchecked")
            Map<String, Object> jsonNode = objectMapper.readValue(jsonResponse, Map.class);

            DocumentExtractionResult result = new DocumentExtractionResult();
            result.setStatus((String) jsonNode.get("status"));

            if ("success".equals(result.getStatus())) {
                result.setDocumentType((String) jsonNode.get("documentType"));
                result.setIssuingCountry((String) jsonNode.get("issuingCountry"));
                result.setConfidence((String) jsonNode.get("confidence"));

                log.debug("Document extrait avec succès - Type: {}, Pays: {}, Confiance: {}",
                        result.getDocumentType(), result.getIssuingCountry(), result.getConfidence());

                // Extraction des données du document
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) jsonNode.get("data");
                if (data != null) {
                    result.setExtractedData(data);
                    log.debug("Données extraites: {} champs", data.size());
                }

                // Gestion du côté pour les cartes d'identité
                if (jsonNode.containsKey("side")) {
                    result.setSide((String) jsonNode.get("side"));
                    log.debug("Côté détecté: {}", result.getSide());
                }

                // Gestion des données recto/verso séparées
                if (jsonNode.containsKey("recto_data")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rectoData = (Map<String, Object>) jsonNode.get("recto_data");
                    result.setRectoData(rectoData);
                    log.debug("Données recto: {} champs", rectoData != null ? rectoData.size() : 0);
                }

                if (jsonNode.containsKey("verso_data")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> versoData = (Map<String, Object>) jsonNode.get("verso_data");
                    result.setVersoData(versoData);
                    log.debug("Données verso: {} champs", versoData != null ? versoData.size() : 0);
                }

            } else {
                result.setError((String) jsonNode.get("error"));
                log.warn("Échec extraction document: {}", result.getError());

                // Log traceback si disponible pour debug
                if (jsonNode.containsKey("traceback")) {
                    log.debug("Traceback serveur IA: {}", jsonNode.get("traceback"));
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Erreur parsing réponse extraction: {}", jsonResponse, e);
            throw new IOException("Erreur parsing réponse serveur IA: " + jsonResponse, e);
        }
    }

    /**
     * Classe de résultat
     */
    public static class DocumentExtractionResult {
        private String status;
        private String documentType;
        private String issuingCountry;
        private String confidence;
        private String side;
        private Map<String, Object> extractedData;
        private Map<String, Object> rectoData;
        private Map<String, Object> versoData;
        private String error;

        // Getters et Setters (identiques à la version précédente)
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getDocumentType() { return documentType; }
        public void setDocumentType(String documentType) { this.documentType = documentType; }

        public String getIssuingCountry() { return issuingCountry; }
        public void setIssuingCountry(String issuingCountry) { this.issuingCountry = issuingCountry; }

        public String getConfidence() { return confidence; }
        public void setConfidence(String confidence) { this.confidence = confidence; }

        public Map<String, Object> getExtractedData() { return extractedData; }
        public void setExtractedData(Map<String, Object> extractedData) { this.extractedData = extractedData; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public boolean isSuccessful() {
            return "success".equals(status);
        }

        public String getSide() { return side; }
        public void setSide(String side) { this.side = side; }

        public Map<String, Object> getRectoData() { return rectoData; }
        public void setRectoData(Map<String, Object> rectoData) { this.rectoData = rectoData; }

        public Map<String, Object> getVersoData() { return versoData; }
        public void setVersoData(Map<String, Object> versoData) { this.versoData = versoData; }

        public boolean isRectoVersoExtraction() {
            return "BOTH".equals(side) && rectoData != null && versoData != null;
        }

        public Map<String, Object> getAllExtractedData() {
            if (extractedData != null) {
                return extractedData;
            }
            if (rectoData != null) {
                return rectoData;
            }
            if (versoData != null) {
                return versoData;
            }
            return java.util.Map.of();
        }
    }
}