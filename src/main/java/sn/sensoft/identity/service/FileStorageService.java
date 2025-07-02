package sn.sensoft.identity.service;

import io.micronaut.http.MediaType;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.multipart.CompletedFileUpload;
import sn.sensoft.identity.entity.FileType;
import sn.sensoft.identity.entity.VerificationFile;
import sn.sensoft.identity.repository.VerificationFileRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Singleton
public class FileStorageService {

    private final OpenKMService openKMService;
    private final VerificationFileRepository fileRepository;
    private final boolean openKMEnabled;
    private final String localBasePath;

    @Inject
    public FileStorageService(OpenKMService openKMService,
                              VerificationFileRepository fileRepository,
                              @Value("${app.openkm.enabled:true}") boolean openKMEnabled,
                              @Value("${app.file-storage.base-path}") String localBasePath) {
        this.openKMService = openKMService;
        this.fileRepository = fileRepository;
        this.openKMEnabled = openKMEnabled;
        this.localBasePath = localBasePath;

        if (!openKMEnabled) {
            createLocalDirectories();
        }
    }

    // MÉTHODES PUBLIQUES

    @Transactional
    public FileStorageResult saveIdentityDocument(CompletedFileUpload file, String sessionId) throws IOException {
        if (openKMEnabled) {
            return saveFileToOpenKM(file, sessionId, FileType.IDENTITY_DOCUMENT);
        } else {
            return saveFileLocally(file, sessionId, FileType.IDENTITY_DOCUMENT, "identity_documents");
        }
    }

    @Transactional
    public FileStorageResult saveUserPhoto(CompletedFileUpload file, String sessionId) throws IOException {
        if (openKMEnabled) {
            return saveFileToOpenKM(file, sessionId, FileType.USER_PHOTO);
        } else {
            return saveFileLocally(file, sessionId, FileType.USER_PHOTO, "user_photos");
        }
    }

    // RÉCUPÉRATION DE FICHIERS POUR SCRIPTS PYTHON

    public String getFilePathForProcessing(String sessionId, FileType fileType) throws IOException {
        VerificationFile verificationFile = fileRepository.findBySessionIdAndFileType(sessionId, fileType)
                .orElseThrow(() -> new IOException("Fichier non trouvé pour la session: " + sessionId + ", type: " + fileType));

        if (openKMEnabled) {
            return getOpenKMFileForProcessing(verificationFile);
        } else {
            // Mode local - retourner directement le chemin
            return verificationFile.getOpenkmPath(); // En mode local, on stocke le chemin local ici
        }
    }

    private String getOpenKMFileForProcessing(VerificationFile verificationFile) throws IOException {
        // Vérifier si un fichier temporaire existe et n'est pas expiré
        if (verificationFile.getTempPath() != null && !verificationFile.isTempFileExpired()) {
            // Vérifier que le fichier existe physiquement
            if (Files.exists(Paths.get(verificationFile.getTempPath()))) {
                return verificationFile.getTempPath();
            }
        }

        // Télécharger depuis OpenKM vers un fichier temporaire
        OpenKMService.TempFileResult tempResult = openKMService.downloadToTempFile(
                verificationFile.getOpenkmUuid(),
                verificationFile.getOpenkmPath()
        );

        if (!tempResult.isSuccess()) {
            throw new IOException("Erreur téléchargement depuis OpenKM: " + tempResult.getError());
        }

        // Mettre à jour l'enregistrement avec le chemin temporaire
        verificationFile.setTempFile(tempResult.getTempFilePath(), 30); // 30 minutes
        fileRepository.update(verificationFile);

        return tempResult.getTempFilePath();
    }

    // SAUVEGARDE OPENKM

    private FileStorageResult saveFileToOpenKM(CompletedFileUpload file, String sessionId, FileType fileType) throws IOException {
        try {
            // Upload vers OpenKM
            OpenKMService.OpenKMUploadResult uploadResult;
            if (fileType == FileType.IDENTITY_DOCUMENT) {
                uploadResult = openKMService.uploadIdentityDocument(file);
            } else {
                uploadResult = openKMService.uploadUserPhoto(file);
            }

            if (!uploadResult.isSuccess()) {
                return FileStorageResult.error("Erreur upload OpenKM: " + uploadResult.getError());
            }

            // Sauvegarder les métadonnées en PostgreSQL
            VerificationFile verificationFile = new VerificationFile(
                    sessionId,
                    fileType,
                    uploadResult.getOpenkmUuid(),
                    uploadResult.getOpenkmPath(),
                    uploadResult.getOpenkmFolder()
            );

            verificationFile.setOriginalFilename(uploadResult.getOriginalFilename());
            verificationFile.setContentType(uploadResult.getContentType());
            verificationFile.setFileSize(uploadResult.getFileSize());

            verificationFile = fileRepository.save(verificationFile);

            return FileStorageResult.success(
                    verificationFile.getId(),
                    uploadResult.getOpenkmUuid(),
                    uploadResult.getOpenkmPath(),
                    uploadResult.getOriginalFilename()
            );

        } catch (Exception e) {
            throw new IOException("Erreur sauvegarde OpenKM: " + e.getMessage(), e);
        }
    }

    // SAUVEGARDE LOCALE (Fallback)

    private FileStorageResult saveFileLocally(CompletedFileUpload file, String sessionId,
                                              FileType fileType, String subDirectory) throws IOException {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = timestamp + "_" + UUID.randomUUID().toString() + "_" + file.getFilename();

            Path directory = Paths.get(localBasePath, subDirectory);
            Path filePath = directory.resolve(filename);

            Files.copy(file.getInputStream(), filePath);

            // Sauvegarder les métadonnées en PostgreSQL (même en mode local)
            VerificationFile verificationFile = new VerificationFile(
                    sessionId,
                    fileType,
                    UUID.randomUUID().toString(), // UUID local généré
                    filePath.toString(), // Chemin local stocké comme "openkmPath"
                    subDirectory
            );

            verificationFile.setOriginalFilename(file.getFilename());
            verificationFile.setContentType(file.getContentType().map(MediaType::toString).orElse("application/octet-stream"));
            verificationFile.setFileSize(file.getSize());

            verificationFile = fileRepository.save(verificationFile);

            return FileStorageResult.success(
                    verificationFile.getId(),
                    verificationFile.getOpenkmUuid(),
                    filePath.toString(),
                    file.getFilename()
            );

        } catch (Exception e) {
            throw new IOException("Erreur sauvegarde locale: " + e.getMessage(), e);
        }
    }

    // NETTOYAGE

    public boolean deleteFile(UUID fileId) {
        try {
            VerificationFile verificationFile = fileRepository.findById(fileId).orElse(null);
            if (verificationFile == null) {
                return false;
            }

            if (openKMEnabled) {
                // TODO: Implémenter la suppression OpenKM via API REST
                // Pour le moment, on supprime juste l'enregistrement
            } else {
                // Suppression locale
                try {
                    Files.deleteIfExists(Paths.get(verificationFile.getOpenkmPath()));
                } catch (IOException e) {
                    System.err.println("Erreur suppression fichier local: " + e.getMessage());
                }
            }

            // Supprimer le fichier temporaire s'il existe
            if (verificationFile.getTempPath() != null) {
                openKMService.deleteTempFile(verificationFile.getTempPath());
            }

            // Supprimer l'enregistrement
            fileRepository.deleteById(fileId);
            return true;

        } catch (Exception e) {
            System.err.println("Erreur suppression fichier: " + e.getMessage());
            return false;
        }
    }

    public void cleanupExpiredTempFiles() {
        if (openKMEnabled) {
            // Nettoyer les fichiers temporaires OpenKM
            openKMService.cleanupExpiredTempFiles();

            // Nettoyer les enregistrements de fichiers temporaires expirés
            fileRepository.deleteByTempExpiresAtBeforeAndTempPathIsNotNull(LocalDateTime.now());
        }
    }

    // MÉTHODES UTILITAIRES

    private void createLocalDirectories() {
        try {
            Files.createDirectories(Paths.get(localBasePath, "identity_documents"));
            Files.createDirectories(Paths.get(localBasePath, "user_photos"));
            System.out.println("Dossiers locaux créés: " + localBasePath);
        } catch (IOException e) {
            throw new RuntimeException("Impossible de créer les répertoires locaux", e);
        }
    }

    public VerificationFile getFileMetadata(String sessionId, FileType fileType) {
        return fileRepository.findBySessionIdAndFileType(sessionId, fileType).orElse(null);
    }

    public boolean isOpenKMEnabled() {
        return openKMEnabled;
    }

    // CLASSE DE RÉSULTAT

    public static class FileStorageResult {
        private final boolean success;
        private final UUID fileId;
        private final String fileReference; // UUID OpenKM ou chemin local
        private final String filePath;
        private final String originalFilename;
        private final String error;

        private FileStorageResult(boolean success, UUID fileId, String fileReference,
                                  String filePath, String originalFilename, String error) {
            this.success = success;
            this.fileId = fileId;
            this.fileReference = fileReference;
            this.filePath = filePath;
            this.originalFilename = originalFilename;
            this.error = error;
        }

        public static FileStorageResult success(UUID fileId, String fileReference,
                                                String filePath, String originalFilename) {
            return new FileStorageResult(true, fileId, fileReference, filePath, originalFilename, null);
        }

        public static FileStorageResult error(String error) {
            return new FileStorageResult(false, null, null, null, null, error);
        }

        // Getters
        public boolean isSuccess() { return success; }
        public UUID getFileId() { return fileId; }
        public String getFileReference() { return fileReference; }
        public String getFilePath() { return filePath; }
        public String getOriginalFilename() { return originalFilename; }
        public String getError() { return error; }
    }
}