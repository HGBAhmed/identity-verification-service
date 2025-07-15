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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Singleton
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

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

        log.info("FileStorageService initialisé - OpenKM: {}, Chemin local: {}", openKMEnabled, localBasePath);

        if (!openKMEnabled) {
            createLocalDirectories();
        }
    }

    // MÉTHODES PUBLIQUES

    @Transactional
    public FileStorageResult saveIdentityDocument(CompletedFileUpload file, String sessionId) throws IOException {
        log.debug("Sauvegarde document d'identité pour session: {}, fichier: {}", sessionId, file.getFilename());

        if (openKMEnabled) {
            return saveFileToOpenKM(file, sessionId, FileType.IDENTITY_DOCUMENT);
        } else {
            return saveFileLocally(file, sessionId, FileType.IDENTITY_DOCUMENT, "identity_documents");
        }
    }

    @Transactional
    public FileStorageResult saveUserPhoto(CompletedFileUpload file, String sessionId) throws IOException {
        log.debug("Sauvegarde photo utilisateur pour session: {}, fichier: {}", sessionId, file.getFilename());

        if (openKMEnabled) {
            return saveFileToOpenKM(file, sessionId, FileType.USER_PHOTO);
        } else {
            return saveFileLocally(file, sessionId, FileType.USER_PHOTO, "user_photos");
        }
    }

    // RÉCUPÉRATION DE FICHIERS POUR SCRIPTS PYTHON

    public String getFilePathForProcessing(String sessionId, FileType fileType) throws IOException {
        log.debug("Récupération chemin fichier pour traitement - Session: {}, Type: {}", sessionId, fileType);

        VerificationFile verificationFile = fileRepository.findBySessionIdAndFileType(sessionId, fileType)
                .orElseThrow(() -> new IOException("Fichier non trouvé pour la session: " + sessionId + ", type: " + fileType));

        if (openKMEnabled) {
            return getOpenKMFileForProcessing(verificationFile);
        } else {
            // Mode local - retourner directement le chemin
            log.debug("Mode local - Retour du chemin: {}", verificationFile.getOpenkmPath());
            return verificationFile.getOpenkmPath(); // En mode local, on stocke le chemin local ici
        }
    }

    private String getOpenKMFileForProcessing(VerificationFile verificationFile) throws IOException {
        log.debug("Récupération fichier OpenKM pour traitement - UUID: {}", verificationFile.getOpenkmUuid());

        // Vérifier si un fichier temporaire existe et n'est pas expiré
        if (verificationFile.getTempPath() != null && !verificationFile.isTempFileExpired()) {
            // Vérifier que le fichier existe physiquement
            if (Files.exists(Paths.get(verificationFile.getTempPath()))) {
                log.debug("Fichier temporaire valide trouvé: {}", verificationFile.getTempPath());
                return verificationFile.getTempPath();
            }
            log.debug("Fichier temporaire inexistant, re-téléchargement nécessaire");
        }

        // Télécharger depuis OpenKM vers un fichier temporaire
        log.debug("Téléchargement depuis OpenKM - UUID: {}, Path: {}",
                verificationFile.getOpenkmUuid(), verificationFile.getOpenkmPath());

        OpenKMService.TempFileResult tempResult = openKMService.downloadToTempFile(
                verificationFile.getOpenkmUuid(),
                verificationFile.getOpenkmPath()
        );

        if (!tempResult.isSuccess()) {
            log.error("Échec téléchargement depuis OpenKM: {}", tempResult.getError());
            throw new IOException("Erreur téléchargement depuis OpenKM: " + tempResult.getError());
        }

        // Mettre à jour l'enregistrement avec le chemin temporaire
        verificationFile.setTempFile(tempResult.getTempFilePath(), 30); // 30 minutes
        fileRepository.update(verificationFile);

        log.info("Fichier temporaire créé avec succès: {}", tempResult.getTempFilePath());
        return tempResult.getTempFilePath();
    }

    // SAUVEGARDE OPENKM

    private FileStorageResult saveFileToOpenKM(CompletedFileUpload file, String sessionId, FileType fileType) throws IOException {
        try {
            log.debug("Upload vers OpenKM - Session: {}, Type: {}, Fichier: {}", sessionId, fileType, file.getFilename());

            // Upload vers OpenKM
            OpenKMService.OpenKMUploadResult uploadResult;
            if (fileType == FileType.IDENTITY_DOCUMENT) {
                uploadResult = openKMService.uploadIdentityDocument(file);
            } else {
                uploadResult = openKMService.uploadUserPhoto(file);
            }

            if (!uploadResult.isSuccess()) {
                log.error("Échec upload OpenKM pour session {}: {}", sessionId, uploadResult.getError());
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

            log.info("Fichier sauvegardé avec succès dans OpenKM - Session: {}, UUID: {}, Taille: {} bytes",
                    sessionId, uploadResult.getOpenkmUuid(), uploadResult.getFileSize());

            return FileStorageResult.success(
                    verificationFile.getId(),
                    uploadResult.getOpenkmUuid(),
                    uploadResult.getOpenkmPath(),
                    uploadResult.getOriginalFilename()
            );

        } catch (Exception e) {
            log.error("Erreur sauvegarde OpenKM pour session {}: {}", sessionId, e.getMessage(), e);
            throw new IOException("Erreur sauvegarde OpenKM: " + e.getMessage(), e);
        }
    }

    // SAUVEGARDE LOCALE (Fallback)

    private FileStorageResult saveFileLocally(CompletedFileUpload file, String sessionId,
                                              FileType fileType, String subDirectory) throws IOException {
        try {
            log.debug("Sauvegarde locale - Session: {}, Type: {}, Dossier: {}", sessionId, fileType, subDirectory);

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

            log.info("Fichier sauvegardé localement - Session: {}, Chemin: {}, Taille: {} bytes",
                    sessionId, filePath, file.getSize());

            return FileStorageResult.success(
                    verificationFile.getId(),
                    verificationFile.getOpenkmUuid(),
                    filePath.toString(),
                    file.getFilename()
            );

        } catch (Exception e) {
            log.error("Erreur sauvegarde locale pour session {}: {}", sessionId, e.getMessage(), e);
            throw new IOException("Erreur sauvegarde locale: " + e.getMessage(), e);
        }
    }

    // NETTOYAGE

    public boolean deleteFile(UUID fileId) {
        try {
            log.debug("Suppression fichier: {}", fileId);

            VerificationFile verificationFile = fileRepository.findById(fileId).orElse(null);
            if (verificationFile == null) {
                log.warn("Fichier non trouvé pour suppression: {}", fileId);
                return false;
            }

            if (openKMEnabled) {
                // TODO: Implémenter la suppression OpenKM via API REST
                // Pour le moment, on supprime juste l'enregistrement
                log.debug("Suppression OpenKM non implémentée, suppression de l'enregistrement seulement");
            } else {
                // Suppression locale
                try {
                    boolean deleted = Files.deleteIfExists(Paths.get(verificationFile.getOpenkmPath()));
                    log.debug("Fichier local supprimé: {} - Succès: {}", verificationFile.getOpenkmPath(), deleted);
                } catch (IOException e) {
                    log.error("Erreur suppression fichier local {}: {}", verificationFile.getOpenkmPath(), e.getMessage());
                }
            }

            // Supprimer le fichier temporaire s'il existe
            if (verificationFile.getTempPath() != null) {
                openKMService.deleteTempFile(verificationFile.getTempPath());
            }

            // Supprimer l'enregistrement
            fileRepository.deleteById(fileId);
            log.info("Fichier supprimé avec succès: {}", fileId);
            return true;

        } catch (Exception e) {
            log.error("Erreur suppression fichier {}: {}", fileId, e.getMessage(), e);
            return false;
        }
    }

    public void cleanupExpiredTempFiles() {
        log.debug("Démarrage nettoyage fichiers temporaires expirés");

        if (openKMEnabled) {
            // Nettoyer les fichiers temporaires OpenKM
            openKMService.cleanupExpiredTempFiles();

            // Nettoyer les enregistrements de fichiers temporaires expirés
            int deletedCount = fileRepository.deleteByTempExpiresAtBeforeAndTempPathIsNotNull(LocalDateTime.now());
            if (deletedCount > 0) {
                log.info("Nettoyage: {} enregistrements de fichiers temporaires expirés supprimés", deletedCount);
            }
        }
    }

    // MÉTHODES UTILITAIRES

    private void createLocalDirectories() {
        try {
            Files.createDirectories(Paths.get(localBasePath, "identity_documents"));
            Files.createDirectories(Paths.get(localBasePath, "user_photos"));
            log.info("Dossiers locaux créés avec succès: {}", localBasePath);
        } catch (IOException e) {
            log.error("Impossible de créer les répertoires locaux: {}", localBasePath, e);
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