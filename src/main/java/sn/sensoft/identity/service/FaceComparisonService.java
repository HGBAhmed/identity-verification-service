package sn.sensoft.identity.service;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Singleton
public class FaceComparisonService {

    private final String pythonScriptPath;
    private final ObjectMapper objectMapper;

    public FaceComparisonService(@Value("${app.face-comparison.python-script-path}") String pythonScriptPath,
                                 ObjectMapper objectMapper) {
        this.pythonScriptPath = pythonScriptPath;
        this.objectMapper = objectMapper;
    }

    public FaceComparisonResult compareImages(String imagePath1, String imagePath2)
            throws IOException, InterruptedException {

        ProcessBuilder processBuilder = new ProcessBuilder(
                "python", pythonScriptPath, imagePath1, imagePath2
        );

        Process process = processBuilder.start();
        boolean finished = process.waitFor(300, TimeUnit.SECONDS); // 5 minutes

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Face comparison timeout after 300 seconds");
        }

        String output = new String(process.getInputStream().readAllBytes());
        String errorOutput = new String(process.getErrorStream().readAllBytes());

        if (process.exitValue() != 0) {
            throw new RuntimeException("Python script failed. Error: " + errorOutput + ". Output: " + output);
        }

        return parseComparisonResult(output);
    }

    private FaceComparisonResult parseComparisonResult(String output) throws IOException {
        try {
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
                throw new IOException("No JSON found in output: " + output);
            }

            System.out.println("JSON extrait: " + jsonLine);

            Map<String, Object> jsonNode = objectMapper.readValue(jsonLine, Map.class);

            FaceComparisonResult result = new FaceComparisonResult();
            result.setVerified((Boolean) jsonNode.get("verified"));
            result.setConfidence(((Number) jsonNode.get("confidence")).doubleValue());
            result.setStatus((String) jsonNode.get("status"));

            if (jsonNode.containsKey("error")) {
                result.setError((String) jsonNode.get("error"));
            }

            return result;
        } catch (Exception e) {
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