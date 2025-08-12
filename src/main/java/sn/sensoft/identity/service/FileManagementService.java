package sn.sensoft.identity.service;

import io.micronaut.http.server.types.files.StreamedFile;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sn.sensoft.identity.dto.FileInfoDto;
import sn.sensoft.identity.entity.VerificationFile;
import sn.sensoft.identity.entity.VerificationResult;
import sn.sensoft.identity.repository.VerificationFileRepository;
import sn.sensoft.identity.repository.VerificationResultRepository;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import io.micronaut.http.MediaType;

@Singleton
public class FileManagementService {

    private static final Logger log = LoggerFactory.getLogger(FileManagementService.class);

    @Inject
    private VerificationFileRepository fileRepository;

    @Inject
    private VerificationResultRepository resultRepository;

    @Inject
    private OpenKMService openKMService;

    @Inject
    private FileStorageService fileStorageService;

    /**
     * Télécharge un fichier par son ID
     */
    public FileDownloadResult downloadFile(UUID fileId) {
        try {
            log.debug("Début téléchargement fichier: {}", fileId);

            VerificationFile file = fileRepository.findById(fileId).orElse(null);
            if (file == null) {
                log.warn("Fichier non trouvé: {}", fileId);
                return FileDownloadResult.error("Fichier non trouvé");
            }

            return downloadFromOpenKM(file);

        } catch (Exception e) {
            log.error("Erreur téléchargement fichier {}: {}", fileId, e.getMessage(), e);
            return FileDownloadResult.error("Erreur téléchargement: " + e.getMessage());
        }
    }

        /**
         * Récupère les fichiers d'une session
         */
    public List<FileInfoDto> getSessionFiles(String sessionId) {
        log.debug("Récupération fichiers pour session: {}", sessionId);

        List<VerificationFile> files = fileRepository.findBySessionId(sessionId);

        return files.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    /**
     * Récupère les fichiers d'un résultat de vérification
     */
    public List<FileInfoDto> getResultFiles(String requestId) {
        log.debug("Récupération fichiers pour résultat: {}", requestId);

        VerificationResult result = resultRepository.findByRequestId(requestId).orElse(null);
        if (result == null) {
            log.warn("Résultat non trouvé: {}", requestId);
            return List.of();
        }

        List<VerificationFile> files = List.of();

        // Récupérer les fichiers associés
        if (result.getDocumentFileId() != null) {
            fileRepository.findById(result.getDocumentFileId())
                    .ifPresent(file -> files.add(file));
        }

        if (result.getPhotoFileId() != null) {
            fileRepository.findById(result.getPhotoFileId())
                    .ifPresent(file -> files.add(file));
        }

        // Alternative si pas de références directes : chercher par session
        if (files.isEmpty() && result.getSessionId() != null) {
            return getSessionFiles(result.getSessionId());
        }

        return files.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    /**
     * Récupère les métadonnées d'un fichier
     */
    public FileInfoDto getFileInfo(UUID fileId) {
        log.debug("Récupération info fichier: {}", fileId);

        VerificationFile file = fileRepository.findById(fileId).orElse(null);
        if (file == null) {
            log.warn("Fichier non trouvé: {}", fileId);
            return null;
        }

        return convertToDto(file);
    }

    /**
     * Restaure un fichier supprimé (soft delete)
     */
    public boolean restoreFile(UUID fileId) {
        try {
            log.debug("Restauration fichier: {}", fileId);

            VerificationFile file = fileRepository.findById(fileId).orElse(null);
            if (file == null) {
                log.warn("Fichier non trouvé pour restauration: {}", fileId);
                return false;
            }

            if (file.getDeletedAt() == null) {
                log.warn("Fichier non supprimé, impossible de restaurer: {}", fileId);
                return false;
            }

            file.setDeletedAt(null); // Restaurer
            fileRepository.update(file);

            log.info("Fichier restauré avec succès: {}", fileId);
            return true;

        } catch (Exception e) {
            log.error("Erreur restauration fichier {}: {}", fileId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Suppression physique définitive d'un fichier
     */
    public boolean permanentDeleteFile(UUID fileId) {
        try {
            log.debug("Suppression physique définitive fichier: {}", fileId);
            return fileStorageService.permanentDeleteFile(fileId);
        } catch (Exception e) {
            log.error("Erreur suppression définitive fichier {}: {}", fileId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Récupère la liste des fichiers supprimés
     */
    public List<FileInfoDto> getDeletedFiles() {
        try {
            log.debug("Récupération fichiers supprimés");

            List<VerificationFile> deletedFiles = fileRepository.findDeletedFiles();

            return deletedFiles.stream()
                    .map(this::convertToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Erreur récupération fichiers supprimés: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Supprime un fichier
     */
    public boolean deleteFile(UUID fileId) {
        log.debug("Suppression fichier: {}", fileId);
        return fileStorageService.deleteFile(fileId);
    }

    // MÉTHODES PRIVÉES

    private FileDownloadResult downloadFromOpenKM(VerificationFile file) {
        try {
            log.debug("Téléchargement depuis OpenKM - UUID: {}", file.getOpenkmUuid());

            OpenKMService.TempFileResult tempResult = openKMService.downloadToTempFile(
                    file.getOpenkmUuid(),
                    file.getOpenkmPath()
            );

            if (!tempResult.isSuccess()) {
                log.error("Échec téléchargement OpenKM: {}", tempResult.getError());
                return FileDownloadResult.error("Erreur OpenKM: " + tempResult.getError());
            }

            // Créer StreamedFile depuis le fichier temporaire
            InputStream inputStream = new FileInputStream(tempResult.getTempFilePath());
            MediaType mediaType = file.getContentType() != null ?
                    MediaType.of(file.getContentType()) : MediaType.APPLICATION_OCTET_STREAM_TYPE;
            StreamedFile streamedFile = new StreamedFile(inputStream, mediaType);

            log.info("Téléchargement OpenKM réussi: {}", file.getOriginalFilename());

            return FileDownloadResult.success(
                    streamedFile,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    tempResult.getTempFilePath()
            );

        } catch (Exception e) {
            log.error("Erreur téléchargement OpenKM pour fichier {}: {}", file.getId(), e.getMessage(), e);
            return FileDownloadResult.error("Erreur téléchargement OpenKM: " + e.getMessage());
        }
    }

    private FileInfoDto convertToDto(VerificationFile file) {
        return new FileInfoDto(
                file.getId(),
                file.getSessionId(),
                file.getFileType(),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getFileSize(),
                file.getCreatedAt()
        );
    }

    // CLASSE DE RÉSULTAT
    public static class FileDownloadResult {
        private final boolean success;
        private final StreamedFile streamedFile;
        private final String originalFilename;
        private final String contentType;
        private final String tempFilePath; // Pour nettoyage
        private final String error;

        private FileDownloadResult(boolean success, StreamedFile streamedFile, String originalFilename,
                                   String contentType, String tempFilePath, String error) {
            this.success = success;
            this.streamedFile = streamedFile;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.tempFilePath = tempFilePath;
            this.error = error;
        }

        public static FileDownloadResult success(StreamedFile streamedFile, String originalFilename,
                                                 String contentType, String tempFilePath) {
            return new FileDownloadResult(true, streamedFile, originalFilename, contentType, tempFilePath, null);
        }

        public static FileDownloadResult error(String error) {
            return new FileDownloadResult(false, null, null, null, null, error);
        }

        // Getters
        public boolean isSuccess() { return success; }
        public StreamedFile getStreamedFile() { return streamedFile; }
        public String getOriginalFilename() { return originalFilename; }
        public String getContentType() { return contentType; }
        public String getTempFilePath() { return tempFilePath; }
        public String getError() { return error; }
    }
}