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

    public static class DocumentExtractionResult {
        private String status;
        private String documentType;
        private String issuingCountry;
        private String confidence;
        private Map<String, Object> extractedData;
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
    }
}