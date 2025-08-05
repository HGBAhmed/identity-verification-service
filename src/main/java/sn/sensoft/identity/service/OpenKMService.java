package sn.sensoft.identity.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.client.multipart.MultipartBody;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import io.micronaut.http.client.multipart.MultipartBody;

@Singleton
public class OpenKMService {

    private static final Logger log = LoggerFactory.getLogger(OpenKMService.class);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final String documentsFolder;
    private final String photosFolder;
    private final String tempFolder;
    private final String tempPath;
    private final boolean enabled;

    @Inject
    public OpenKMService(HttpClient httpClient,
                         @Value("${app.openkm.base-url}") String baseUrl,
                         @Value("${app.openkm.username}") String username,
                         @Value("${app.openkm.password}") String password,
                         @Value("${app.openkm.folders.identity-documents}") String documentsFolder,
                         @Value("${app.openkm.folders.user-photos}") String photosFolder,
                         @Value("${app.openkm.folders.temp}") String tempFolder,
                         @Value("${app.file-storage.temp-path}") String tempPath,
                         @Value("${app.openkm.enabled:false}") boolean enabled) {

        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
        this.documentsFolder = documentsFolder;
        this.photosFolder = photosFolder;
        this.tempFolder = tempFolder;
        this.tempPath = tempPath;
        this.enabled = enabled;

        log.info("OpenKMService initialized - enabled: {}, baseUrl: {}", enabled, baseUrl);

        if (enabled) {
            createTempDirectory();
            testOpenKMConnection();
        }
    }

    // Test de connexion OpenKM
    private void testOpenKMConnection() {
        try {
            String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

            MutableHttpRequest<Object> request = HttpRequest.GET(baseUrl + "/services/rest/auth/login")
                    .header("Authorization", "Basic " + auth);

            log.debug("Test connexion OpenKM vers: {}", baseUrl + "/services/rest/auth/login");

            HttpResponse<String> response = httpClient.toBlocking().exchange(request, String.class);

            if (response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300) {
                log.info("Connexion OpenKM réussie");
            } else {
                log.warn("Problème connexion OpenKM - Status: {}", response.getStatus());
            }
        } catch (Exception e) {
            log.error("Erreur test connexion OpenKM: {}", e.getMessage());
            log.debug("Détail erreur connexion OpenKM", e);
        }
    }

    // ================== UPLOAD METHODS ==================

    public OpenKMUploadResult uploadIdentityDocument(CompletedFileUpload file) throws IOException {
        if (!enabled) {
            throw new IOException("OpenKM est désactivé");
        }
        return uploadFile(file, documentsFolder, "IDENTITY_DOCUMENT");
    }

    public OpenKMUploadResult uploadUserPhoto(CompletedFileUpload file) throws IOException {
        if (!enabled) {
            throw new IOException("OpenKM est désactivé");
        }
        return uploadFile(file, photosFolder, "USER_PHOTO");
    }

    private OpenKMUploadResult uploadFile(CompletedFileUpload file, String folder, String category) throws IOException {
        try {
            log.debug("Upload file vers OpenKM - folder: {}, category: {}, filename: {}",
                    folder, category, file.getFilename());

            if (file.getSize() == 0) {
                throw new IOException("Fichier vide");
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String uniqueId = UUID.randomUUID().toString().substring(0, 8);
            String filename = timestamp + "_" + uniqueId + "_" + file.getFilename();
            String uploadPath = folder + "/" + filename;

            String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

            MultipartBody.Builder builder = MultipartBody.builder()
                    .addPart("docPath", uploadPath);

            if (file.getContentType().isPresent()) {
                builder.addPart("content", file.getFilename(),
                        MediaType.of(file.getContentType().get()), file.getBytes());
            } else {
                builder.addPart("content", file.getFilename(),
                        MediaType.APPLICATION_OCTET_STREAM_TYPE, file.getBytes());
            }

            String fullUrl = baseUrl + "/services/rest/document/createSimple";

            MutableHttpRequest<MultipartBody> request = HttpRequest.POST(fullUrl, builder.build())
                    .header("Authorization", "Basic " + auth)
                    .contentType(MediaType.MULTIPART_FORM_DATA_TYPE);

            log.debug("Requête OpenKM construite vers: {} - Path: {}", fullUrl, uploadPath);

            HttpResponse<String> response = httpClient.toBlocking().exchange(request, String.class);

            log.debug("Réponse OpenKM - Status: {}, Body: {}", response.getStatus(), response.body());

            if (response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300) {
                String openkmUuid = extractUuidFromResponse(response.body(), uploadPath);

                log.info("Upload OpenKM réussi - UUID: {}, Path: {}", openkmUuid, uploadPath);

                return new OpenKMUploadResult(
                        true,
                        openkmUuid,
                        uploadPath,
                        folder,
                        file.getFilename(),
                        file.getContentType().map(MediaType::toString).orElse("application/octet-stream"),
                        file.getSize(),
                        null
                );
            } else {
                String errorMsg = "Erreur OpenKM: " + response.getStatus() + " - " + response.body();
                log.error(errorMsg);
                return new OpenKMUploadResult(
                        false, null, null, null, null, null, 0L, errorMsg
                );
            }

        } catch (Exception e) {
            String errorMsg = "Erreur upload OpenKM: " + e.getMessage();
            log.error(errorMsg, e);
            return new OpenKMUploadResult(
                    false, null, null, null, null, null, 0L, errorMsg
            );
        }
    }


    // DELETE METHODS

    /**
     * Supprime un document dans OpenKM
     */
    public boolean deleteDocument(String openkmUuid, String openkmPath) {
        if (!enabled) {
            log.warn("OpenKM désactivé - impossible de supprimer le document");
            return false;
        }

        try {
            log.debug("Suppression document OpenKM - UUID: {}, Path: {}", openkmUuid, openkmPath);

            String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

            // d'abord par path
            if (openkmPath != null && !openkmPath.isEmpty()) {
                // POST avec form data
                String deleteUrl = baseUrl + "/services/rest/document/delete";

                MultipartBody.Builder builder = MultipartBody.builder()
                        .addPart("docId", openkmPath);

                MutableHttpRequest<MultipartBody> request = HttpRequest.POST(deleteUrl, builder.build())
                        .header("Authorization", "Basic " + auth)
                        .contentType(MediaType.MULTIPART_FORM_DATA_TYPE);

                log.debug("Suppression par path (POST): {} - Path: {}", deleteUrl, openkmPath);

                try {
                    HttpResponse<String> response = httpClient.toBlocking().exchange(request, String.class);

                    if (response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300) {
                        log.info("Document supprimé avec succès dans OpenKM: {}", openkmPath);
                        return true;
                    } else {
                        log.warn("Échec suppression par path (POST) - Status: {}, Response: {}",
                                response.getStatus(), response.body());
                    }
                } catch (Exception e) {
                    log.warn("Erreur suppression POST: {}", e.getMessage());
                }

                //  DELETE avec query param (fallback)
                try {
                    String encodedPath = URLEncoder.encode(openkmPath, StandardCharsets.UTF_8);
                    String deleteUrlGet = baseUrl + "/services/rest/document/delete?docId=" + encodedPath;

                    MutableHttpRequest<Object> requestGet = HttpRequest.DELETE(deleteUrlGet)
                            .header("Authorization", "Basic " + auth);

                    log.debug("Suppression par path (DELETE): {}", deleteUrlGet);

                    HttpResponse<String> responseGet = httpClient.toBlocking().exchange(requestGet, String.class);

                    if (responseGet.getStatus().getCode() >= 200 && responseGet.getStatus().getCode() < 300) {
                        log.info("Document supprimé avec succès dans OpenKM (DELETE): {}", openkmPath);
                        return true;
                    } else {
                        log.warn("Échec suppression par path (DELETE) - Status: {}, Response: {}",
                                responseGet.getStatus(), responseGet.body());
                    }
                } catch (Exception e) {
                    log.warn("Erreur suppression DELETE: {}", e.getMessage());
                }
            }

            // Fallback: essayer par UUID si path échoue
            if (openkmUuid != null && !openkmUuid.isEmpty() && !openkmUuid.startsWith("path:")) {
                // Version 1: POST avec form data
                String deleteUrl = baseUrl + "/services/rest/document/delete";

                MultipartBody.Builder builder = MultipartBody.builder()
                        .addPart("docId", openkmUuid);

                MutableHttpRequest<MultipartBody> request = HttpRequest.POST(deleteUrl, builder.build())
                        .header("Authorization", "Basic " + auth)
                        .contentType(MediaType.MULTIPART_FORM_DATA_TYPE);

                log.debug("Suppression par UUID (POST): {} - UUID: {}", deleteUrl, openkmUuid);

                try {
                    HttpResponse<String> response = httpClient.toBlocking().exchange(request, String.class);

                    if (response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300) {
                        log.info("Document supprimé avec succès dans OpenKM par UUID: {}", openkmUuid);
                        return true;
                    } else {
                        log.warn("Échec suppression par UUID (POST) - Status: {}, Response: {}",
                                response.getStatus(), response.body());
                    }
                } catch (Exception e) {
                    log.warn("Erreur suppression UUID POST: {}", e.getMessage());
                }

                //  DELETE avec query param (fallback)
                try {
                    String deleteUrlGet = baseUrl + "/services/rest/document/delete?docId=" + openkmUuid;

                    MutableHttpRequest<Object> requestGet = HttpRequest.DELETE(deleteUrlGet)
                            .header("Authorization", "Basic " + auth);

                    log.debug("Suppression par UUID (DELETE): {}", deleteUrlGet);

                    HttpResponse<String> responseGet = httpClient.toBlocking().exchange(requestGet, String.class);

                    if (responseGet.getStatus().getCode() >= 200 && responseGet.getStatus().getCode() < 300) {
                        log.info("Document supprimé avec succès dans OpenKM par UUID (DELETE): {}", openkmUuid);
                        return true;
                    } else {
                        log.warn("Échec suppression par UUID (DELETE) - Status: {}, Response: {}",
                                responseGet.getStatus(), responseGet.body());
                    }
                } catch (Exception e) {
                    log.warn("Erreur suppression UUID DELETE: {}", e.getMessage());
                }
            }

            log.error("Impossible de supprimer le document - UUID: {}, Path: {}", openkmUuid, openkmPath);
            return false;

        } catch (Exception e) {
            log.error("Erreur suppression document OpenKM - UUID: {}, Path: {}: {}",
                    openkmUuid, openkmPath, e.getMessage(), e);
            return false;
        }
    }

    // DOWNLOAD METHODS

    public TempFileResult downloadToTempFile(String openkmUuid, String openkmPath) throws IOException {
        if (!enabled) {
            throw new IOException("OpenKM est désactivé");
        }

        try {
            log.debug("Téléchargement depuis OpenKM - UUID: {}, Path: {}", openkmUuid, openkmPath);

            String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

            // Essayer d'abord avec l'UUID
            TempFileResult resultByUuid = downloadByUuid(openkmUuid, auth);
            if (resultByUuid.isSuccess()) {
                return resultByUuid;
            }

            log.warn("Échec téléchargement par UUID, tentative par path...");

            // Fallback avec le path
            return downloadByPath(openkmPath, auth);

        } catch (Exception e) {
            String errorMsg = "Erreur téléchargement: " + e.getMessage();
            log.error(errorMsg, e);
            return new TempFileResult(false, null, null, errorMsg);
        }
    }

    // Téléchargement par UUID
    private TempFileResult downloadByUuid(String openkmUuid, String auth) {
        try {
            log.debug("Tentative téléchargement par UUID: {}", openkmUuid);

            // URL recommandée par OpenKM pour téléchargement par UUID
            String downloadUrl = baseUrl + "/Download?uuid=" + openkmUuid;

            MutableHttpRequest<Object> request = HttpRequest.GET(downloadUrl)
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/octet-stream");

            log.debug("URL téléchargement par UUID: {}", downloadUrl);

            HttpResponse<byte[]> response = httpClient.toBlocking().exchange(request, byte[].class);

            log.debug("Réponse téléchargement UUID - Status: {}, Content-Length: {}",
                    response.getStatus(),
                    response.getHeaders().get("Content-Length"));

            if (response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300 && response.body() != null) {
                return createTempFile(response.body(), openkmUuid);
            } else {
                log.warn("Échec téléchargement UUID - Status: {}", response.getStatus());
                return new TempFileResult(false, null, null, "Échec téléchargement UUID: " + response.getStatus());
            }

        } catch (Exception e) {
            log.warn("Erreur téléchargement par UUID: {}", e.getMessage());
            return new TempFileResult(false, null, null, "Erreur UUID: " + e.getMessage());
        }
    }

    // Téléchargement par path (fallback)
    private TempFileResult downloadByPath(String openkmPath, String auth) {
        try {
            log.debug("Tentative téléchargement par path: {}", openkmPath);

            String encodedPath = URLEncoder.encode(openkmPath, StandardCharsets.UTF_8);
            String downloadUrl = baseUrl + "/services/rest/document/getContent?docPath=" + encodedPath;

            MutableHttpRequest<Object> request = HttpRequest.GET(downloadUrl)
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/octet-stream");

            log.debug("URL téléchargement par path: {}", downloadUrl);

            HttpResponse<byte[]> response = httpClient.toBlocking().exchange(request, byte[].class);

            log.debug("Réponse téléchargement path - Status: {}, Content-Length: {}",
                    response.getStatus(),
                    response.getHeaders().get("Content-Length"));

            if (response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300 && response.body() != null) {
                return createTempFile(response.body(), extractFilenameFromPath(openkmPath));
            } else {
                String errorMsg = "Erreur téléchargement path - Status: " + response.getStatus();
                log.error(errorMsg);
                return new TempFileResult(false, null, null, errorMsg);
            }

        } catch (Exception e) {
            String errorMsg = "Erreur téléchargement par path: " + e.getMessage();
            log.error(errorMsg, e);
            return new TempFileResult(false, null, null, errorMsg);
        }
    }

    // METHODES UTILITAIRES

    // Création du fichier temporaire
    private TempFileResult createTempFile(byte[] content, String identifier) throws IOException {
        if (content == null || content.length == 0) {
            throw new IOException("Contenu vide reçu d'OpenKM");
        }

        String tempFilename = "temp_" + UUID.randomUUID().toString() + "_" + identifier;
        Path tempFilePath = Paths.get(tempPath, tempFilename);

        createTempDirectory();

        try (FileOutputStream fos = new FileOutputStream(tempFilePath.toFile())) {
            fos.write(content);
        }

        log.info("Fichier temporaire créé: {} (taille: {} bytes)", tempFilePath, content.length);

        return new TempFileResult(
                true,
                tempFilePath.toString(),
                LocalDateTime.now().plusMinutes(30),
                null
        );
    }

    // NETTOYAGE
    public boolean deleteTempFile(String tempFilePath) {
        try {
            boolean deleted = Files.deleteIfExists(Paths.get(tempFilePath));
            if (deleted) {
                log.debug("Fichier temporaire supprimé: {}", tempFilePath);
            }
            return deleted;
        } catch (IOException e) {
            log.error("Erreur suppression fichier temporaire: {}", tempFilePath, e);
            return false;
        }
    }

    public void cleanupExpiredTempFiles() {
        try {
            Path tempDir = Paths.get(tempPath);
            if (Files.exists(tempDir)) {
                Files.list(tempDir)
                        .filter(path -> path.getFileName().toString().startsWith("temp_"))
                        .filter(path -> {
                            try {
                                LocalDateTime fileTime = LocalDateTime.ofInstant(
                                        Files.getLastModifiedTime(path).toInstant(),
                                        java.time.ZoneId.systemDefault()
                                );
                                return fileTime.isBefore(LocalDateTime.now().minusMinutes(30));
                            } catch (IOException e) {
                                return true;
                            }
                        })
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                                log.debug("Fichier temporaire expiré supprimé: {}", path);
                            } catch (IOException e) {
                                log.error("Erreur suppression fichier expiré: {}", path, e);
                            }
                        });
            }
        } catch (IOException e) {
            log.error("Erreur nettoyage fichiers temporaires", e);
        }
    }

    private void createTempDirectory() {
        try {
            Path tempDir = Paths.get(tempPath);
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
                log.info("Dossier temporaire créé: {}", tempPath);
            }
        } catch (IOException e) {
            throw new RuntimeException("Impossible de créer le dossier temporaire: " + tempPath, e);
        }
    }

    // Extraction UUID
    private String extractUuidFromResponse(String responseBody, String uploadPath) {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            log.warn("Réponse OpenKM vide, génération UUID local");
            return UUID.randomUUID().toString();
        }

        try {
            // Pattern spécifique pour réponse XML OpenKM
            Pattern xmlUuidPattern = Pattern.compile("<uuid>([a-fA-F0-9-]{36})</uuid>");
            Matcher matcher = xmlUuidPattern.matcher(responseBody);

            if (matcher.find()) {
                String uuid = matcher.group(1);
                log.debug("UUID extrait de la réponse XML OpenKM: {}", uuid);
                return uuid;
            }

            // Fallback: autres patterns
            Pattern generalPattern = Pattern.compile(
                    "(?:\"uuid\"\\s*:\\s*\"([a-fA-F0-9-]{36})\")|" +
                            "([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})"
            );

            Matcher generalMatcher = generalPattern.matcher(responseBody);
            if (generalMatcher.find()) {
                String uuid = generalMatcher.group(1) != null ? generalMatcher.group(1) : generalMatcher.group(2);
                log.debug("UUID extrait avec pattern général: {}", uuid);
                return uuid;
            }

            log.warn("Aucun UUID trouvé dans la réponse OpenKM, utilisation du path: {}", uploadPath);
            return "path:" + uploadPath;

        } catch (Exception e) {
            log.error("Erreur extraction UUID: {}", e.getMessage());
            return UUID.randomUUID().toString();
        }
    }

    private String extractFilenameFromPath(String openkmPath) {
        if (openkmPath == null || openkmPath.isEmpty()) {
            return "unknown_file";
        }
        return openkmPath.substring(openkmPath.lastIndexOf('/') + 1);
    }

    public boolean isEnabled() {
        return enabled;
    }

    // CLASSES DE RESULTATS

    public static class OpenKMUploadResult {
        private final boolean success;
        private final String openkmUuid;
        private final String openkmPath;
        private final String openkmFolder;
        private final String originalFilename;
        private final String contentType;
        private final Long fileSize;
        private final String error;

        public OpenKMUploadResult(boolean success, String openkmUuid, String openkmPath,
                                  String openkmFolder, String originalFilename, String contentType,
                                  Long fileSize, String error) {
            this.success = success;
            this.openkmUuid = openkmUuid;
            this.openkmPath = openkmPath;
            this.openkmFolder = openkmFolder;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.fileSize = fileSize;
            this.error = error;
        }

        public boolean isSuccess() { return success; }
        public String getOpenkmUuid() { return openkmUuid; }
        public String getOpenkmPath() { return openkmPath; }
        public String getOpenkmFolder() { return openkmFolder; }
        public String getOriginalFilename() { return originalFilename; }
        public String getContentType() { return contentType; }
        public Long getFileSize() { return fileSize; }
        public String getError() { return error; }
    }

    public static class TempFileResult {
        private final boolean success;
        private final String tempFilePath;
        private final LocalDateTime expiresAt;
        private final String error;

        public TempFileResult(boolean success, String tempFilePath, LocalDateTime expiresAt, String error) {
            this.success = success;
            this.tempFilePath = tempFilePath;
            this.expiresAt = expiresAt;
            this.error = error;
        }

        public boolean isSuccess() { return success; }
        public String getTempFilePath() { return tempFilePath; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
        public String getError() { return error; }
    }
}