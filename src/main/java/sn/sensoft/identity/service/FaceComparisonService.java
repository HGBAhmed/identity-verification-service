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
public class FaceComparisonService {

    private static final Logger log = LoggerFactory.getLogger(FaceComparisonService.class);

    private final String pythonScriptPath;
    private final ObjectMapper objectMapper;

    public FaceComparisonService(@Value("${app.face-comparison.python-script-path}") String pythonScriptPath,
                                 ObjectMapper objectMapper) {
        this.pythonScriptPath = pythonScriptPath;
        this.objectMapper = objectMapper;
        log.info("FaceComparisonService initialisé avec script: {}", pythonScriptPath);
    }

    public FaceComparisonResult compareImages(String imagePath1, String imagePath2)
            throws IOException, InterruptedException {

        log.debug("Début comparaison faciale entre: {} et {}", imagePath1, imagePath2);

        ProcessBuilder processBuilder = new ProcessBuilder(
                "python", pythonScriptPath, imagePath1, imagePath2
        );

        Process process = processBuilder.start();
        boolean finished = process.waitFor(300, TimeUnit.SECONDS); // 5 minutes

        if (!finished) {
            log.error("Timeout comparaison faciale après 300 secondes pour images: {} et {}",
                    imagePath1, imagePath2);
            process.destroyForcibly();
            throw new RuntimeException("Face comparison timeout after 300 seconds");
        }

        String output = new String(process.getInputStream().readAllBytes());
        String errorOutput = new String(process.getErrorStream().readAllBytes());

        log.debug("Code de sortie comparaison: {} pour images: {} et {}",
                process.exitValue(), imagePath1, imagePath2);

        if (process.exitValue() != 0) {
            log.error("Échec script Python comparaison pour images: {} et {}. Error: {}. Output: {}",
                    imagePath1, imagePath2, errorOutput, output);
            throw new RuntimeException("Python script failed. Error: " + errorOutput + ". Output: " + output);
        }

        log.info("Comparaison faciale réussie pour images: {} et {}", imagePath1, imagePath2);
        return parseComparisonResult(output);
    }

    private FaceComparisonResult parseComparisonResult(String output) throws IOException {
        try {
            log.debug("Parsing résultat comparaison faciale");

            // Extraire seulement la dernière ligne (le JSON)
            String[] lines = output.trim().split("\\r?\\n");
            String jsonLine = "";

            // Chercher la ligne qui contient le JSON (commence par {)
            for (String line : lines) {
                if (line.trim().startsWith("{")) {
                    jsonLine = line.trim();
                }
            }

            if (jsonLine.isEmpty()) {
                log.error("Aucun JSON trouvé dans la sortie: {}", output);
                throw new IOException("No JSON found in output: " + output);
            }

            log.debug("JSON extrait pour comparaison: {}", jsonLine);

            @SuppressWarnings("unchecked")
            Map<String, Object> jsonNode = objectMapper.readValue(jsonLine, Map.class);

            FaceComparisonResult result = new FaceComparisonResult();
            result.setVerified((Boolean) jsonNode.get("verified"));
            result.setConfidence(((Number) jsonNode.get("confidence")).doubleValue());
            result.setStatus((String) jsonNode.get("status"));

            log.info("Comparaison faciale terminée - Vérifié: {}, Confiance: {:.3f}, Statut: {}",
                    result.isVerified(), result.getConfidence(), result.getStatus());

            if (jsonNode.containsKey("error")) {
                result.setError((String) jsonNode.get("error"));
                log.warn("Erreur dans comparaison faciale: {}", result.getError());
            }

            return result;
        } catch (Exception e) {
            log.error("Erreur parsing résultat comparaison faciale: {}", output, e);
            throw new IOException("Failed to parse Python script output: " + output, e);
        }
    }

    public static class FaceComparisonResult {
        private boolean verified;
        private double confidence;
        private String status;
        private String error;

        public boolean isVerified() { return verified; }
        public void setVerified(boolean verified) { this.verified = verified; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}