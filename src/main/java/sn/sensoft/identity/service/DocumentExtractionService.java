package sn.sensoft.identity.service;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import io.micronaut.serde.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Singleton
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);

    private final String pythonExtractionScriptPath;
    private final ObjectMapper objectMapper;

    public DocumentExtractionService(@Value("${app.document-extraction.python-script-path}") String pythonExtractionScriptPath,
                                     ObjectMapper objectMapper) {
        this.pythonExtractionScriptPath = pythonExtractionScriptPath;
        this.objectMapper = objectMapper;
        log.info("DocumentExtractionService initialisé avec script: {}", pythonExtractionScriptPath);
    }

    /**
     * Extraction d'un document unique
     */
    public DocumentExtractionResult extractDocumentData(String imagePath) throws IOException, InterruptedException {
        log.debug("Début extraction document pour image: {}", imagePath);

        ProcessBuilder processBuilder = new ProcessBuilder(
                "python", pythonExtractionScriptPath, imagePath
        );

        Process process = processBuilder.start();
        boolean finished = process.waitFor(300, TimeUnit.SECONDS); // 5 MIN DE TIMEOUT

        if (!finished) {
            log.error("Timeout extraction document après 300 secondes pour image: {}", imagePath);
            process.destroyForcibly();
            throw new RuntimeException("Document extraction timeout after 300 seconds");
        }

        String output = new String(process.getInputStream().readAllBytes());
        String errorOutput = new String(process.getErrorStream().readAllBytes());

        log.debug("Code de sortie extraction: {} pour image: {}", process.exitValue(), imagePath);

        if (process.exitValue() != 0) {
            log.error("Échec script Python extraction pour image: {}. Error: {}. Output: {}",
                    imagePath, errorOutput, output);
            throw new RuntimeException("Python extraction script failed. Error: " + errorOutput + ". Output: " + output);
        }

        log.info("Extraction document réussie pour image: {}", imagePath);
        return parseExtractionResult(output);
    }

    /**
     * Extraction recto/verso pour cartes d'identité
     */
    public DocumentExtractionResult extractRectoVersoData(String rectoPath, String versoPath)
            throws IOException, InterruptedException {

        log.debug("Début extraction recto/verso - Recto: {}, Verso: {}", rectoPath, versoPath);

        // Appeler le script Python avec les deux fichiers
        ProcessBuilder processBuilder = new ProcessBuilder(
                "python", pythonExtractionScriptPath, rectoPath, versoPath
        );

        Process process = processBuilder.start();
        boolean finished = process.waitFor(300, TimeUnit.SECONDS); // 5 MIN DE TIMEOUT

        if (!finished) {
            log.error("Timeout extraction recto/verso après 300 secondes - Recto: {}, Verso: {}",
                    rectoPath, versoPath);
            process.destroyForcibly();
            throw new RuntimeException("Recto/Verso extraction timeout after 300 seconds");
        }

        String output = new String(process.getInputStream().readAllBytes());
        String errorOutput = new String(process.getErrorStream().readAllBytes());

        log.debug("Code de sortie extraction recto/verso: {} - Recto: {}, Verso: {}",
                process.exitValue(), rectoPath, versoPath);

        if (process.exitValue() != 0) {
            log.error("Échec script Python extraction recto/verso - Recto: {}, Verso: {}. Error: {}. Output: {}",
                    rectoPath, versoPath, errorOutput, output);
            throw new RuntimeException("Python recto/verso extraction script failed. Error: " + errorOutput + ". Output: " + output);
        }

        log.info("Extraction recto/verso réussie - Recto: {}, Verso: {}", rectoPath, versoPath);
        return parseExtractionResult(output);
    }

    /**
     * Parsing du résultat JSON
     */
    private DocumentExtractionResult parseExtractionResult(String output) throws IOException {
        try {
            log.debug("Parsing résultat extraction document");

            // JSON multi-lignes
            // Cherche le JSON complet
            int startIndex = output.indexOf('{');
            int endIndex = output.lastIndexOf('}');

            if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) {
                log.error("Aucun JSON valide trouvé dans la sortie extraction: {}", output);
                throw new IOException("No valid JSON found in extraction output: " + output);
            }

            String jsonContent = output.substring(startIndex, endIndex + 1);
            log.debug("JSON extrait pour document: {}", jsonContent);

            Map<String, Object> jsonNode = objectMapper.readValue(jsonContent, Map.class);

            DocumentExtractionResult result = new DocumentExtractionResult();
            result.setStatus((String) jsonNode.get("status"));

            if ("success".equals(result.getStatus())) {
                result.setDocumentType((String) jsonNode.get("documentType"));
                result.setIssuingCountry((String) jsonNode.get("issuingCountry"));
                result.setConfidence((String) jsonNode.get("confidence"));

                log.info("Document extrait avec succès - Type: {}, Pays: {}, Confiance: {}",
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
            }

            return result;
        } catch (Exception e) {
            log.error("Erreur parsing résultat extraction: {}", output, e);
            throw new IOException("Failed to parse extraction script output: " + output, e);
        }
    }

    /**
     * Classe de résultat enrichie
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

        // Getters et Setters
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

        /**
         * Méthode utilitaire pour vérifier si c'est une extraction recto/verso
         */
        public boolean isRectoVersoExtraction() {
            return "BOTH".equals(side) && rectoData != null && versoData != null;
        }

        /**
         * Méthode utilitaire pour obtenir toutes les données combinées
         */
        public Map<String, Object> getAllExtractedData() {
            if (extractedData != null) {
                return extractedData; // Données déjà fusionnées
            }

            // Si pas de données fusionnées, retourner les données du côté principal
            if (rectoData != null) {
                return rectoData;
            }

            if (versoData != null) {
                return versoData;
            }

            return java.util.Map.of(); // Map vide
        }
    }
}