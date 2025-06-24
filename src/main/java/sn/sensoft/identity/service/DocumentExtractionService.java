package sn.sensoft.identity.service;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Singleton
public class DocumentExtractionService {

    private final String pythonExtractionScriptPath;
    private final ObjectMapper objectMapper;

    public DocumentExtractionService(@Value("${app.document-extraction.python-script-path}") String pythonExtractionScriptPath,
                                     ObjectMapper objectMapper) {
        this.pythonExtractionScriptPath = pythonExtractionScriptPath;
        this.objectMapper = objectMapper;
    }

    public DocumentExtractionResult extractDocumentData(String imagePath) throws IOException, InterruptedException {

        ProcessBuilder processBuilder = new ProcessBuilder(
                "python", pythonExtractionScriptPath, imagePath
        );

        Process process = processBuilder.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS); // 2 MIN DE TIMEOUT

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Document extraction timeout after 120 seconds");
        }

        String output = new String(process.getInputStream().readAllBytes());
        String errorOutput = new String(process.getErrorStream().readAllBytes());

        if (process.exitValue() != 0) {
            throw new RuntimeException("Python extraction script failed. Error: " + errorOutput + ". Output: " + output);
        }

        return parseExtractionResult(output);
    }

    private DocumentExtractionResult parseExtractionResult(String output) throws IOException {
        try {
            // JSON multi-lignes
            // Cherche le JSON complet

            int startIndex = output.indexOf('{');
            int endIndex = output.lastIndexOf('}');

            if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) {
                throw new IOException("No valid JSON found in extraction output: " + output);
            }

            String jsonContent = output.substring(startIndex, endIndex + 1);
            System.out.println("JSON extrait pour document: " + jsonContent);

            Map<String, Object> jsonNode = objectMapper.readValue(jsonContent, Map.class);

            DocumentExtractionResult result = new DocumentExtractionResult();
            result.setStatus((String) jsonNode.get("status"));

            if ("success".equals(result.getStatus())) {
                result.setDocumentType((String) jsonNode.get("documentType"));
                result.setIssuingCountry((String) jsonNode.get("issuingCountry"));
                result.setConfidence((String) jsonNode.get("confidence"));

                // Extraire les données du document
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) jsonNode.get("data");
                if (data != null) {
                    result.setExtractedData(data);
                }
            } else {
                result.setError((String) jsonNode.get("error"));
            }

            return result;
        } catch (Exception e) {
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