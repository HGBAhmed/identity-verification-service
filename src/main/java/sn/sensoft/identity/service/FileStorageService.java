package sn.sensoft.identity.service;

import io.micronaut.http.MediaType;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.multipart.CompletedFileUpload;
import sn.sensoft.identity.entity.FileType;
import sn.sensoft.identity.entity.VerificationFile;
import sn.sensoft.identity.repository.VerificationFileRepository;
import sn.sensoft.identity.util.FileValidator;
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

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Paths;


@Singleton
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final OpenKMService openKMService;
    private final VerificationFileRepository fileRepository;
    private final FileValidator fileValidator;
    private final PdfConversionService pdfConversionService;
    private final boolean openKMEnabled;
    private final String localBasePath;

    @Inject
    public FileStorageService(OpenKMService openKMService,
                              VerificationFileRepository fileRepository,
                              FileValidator fileValidator,
                              PdfConversionService pdfConversionService,
                              @Value("${app.openkm.enabled:true}") boolean openKMEnabled,
                              @Value("${app.file-storage.base-path}") String localBasePath) {
        this.openKMService = openKMService;
        this.fileRepository = fileRepository;
        this.fileValidator = fileValidator;
        this.pdfConversionService = pdfConversionService;
        this.openKMEnabled = openKMEnabled;
        this.localBasePath = localBasePath;

        log.info("FileStorageService initialisé - OpenKM: {}, Chemin local: {}, Support PDF: activé",
                openKMEnabled, localBasePath);

        if (!openKMEnabled) {
            createLocalDirectories();
        }
    }

    // MÉTHODES PUBLIQUES

    @Transactional
    public FileStorageResult saveIdentityDocument(CompletedFileUpload file, String sessionId) throws IOException {
        log.debug("Sauvegarde document d'identité pour session: {}, fichier: {}", sessionId, file.getFilename());
        return saveFileWithPdfSupport(file, sessionId, FileType.IDENTITY_DOCUMENT);
    }

    @Transactional
    public FileStorageResult saveUserPhoto(CompletedFileUpload file, String sessionId) throws IOException {
        log.debug("Sauvegarde photo utilisateur pour session: {}, fichier: {}", sessionId, file.getFilename());

        // Les photos utilisateur ne doivent pas être des PDF
        if (fileValidator.isPDF(file)) {
            throw new IOException("Les photos utilisateur ne peuvent pas être des fichiers PDF");
        }

        return saveFileWithPdfSupport(file, sessionId, FileType.USER_PHOTO);
    }

    // Sauvegarde avec support PDF ET gestion des bytes cachés
    private FileStorageResult saveFileWithPdfSupport(CompletedFileUpload originalFile,
                                                     String sessionId, FileType fileType) throws IOException {
        try {
            log.debug("Traitement fichier avec support PDF - Session: {}, Type: {}, Fichier: {}",
                    sessionId, fileType, originalFile.getFilename());

            CompletedFileUpload fileToProcess = originalFile;
            String conversionInfo = null;

            //Vérifier si on a des bytes en cache
            byte[] cachedBytes = fileValidator.getCachedBytes(originalFile);
            boolean hasCachedBytes = (cachedBytes != null);

            log.debug("Bytes en cache disponibles: {} pour fichier: {}", hasCachedBytes, originalFile.getFilename());

            // Vérification et conversion PDF si nécessaire
            if (fileValidator.isPDF(originalFile)) {
                log.info("Fichier PDF détecté, conversion en cours - Fichier: {}", originalFile.getFilename());

                PdfConversionService.PdfConversionResult conversionResult =
                        pdfConversionService.convertPdfToImage(originalFile);

                if (!conversionResult.isSuccess()) {
                    throw new IOException("Échec conversion PDF: " + conversionResult.getError());
                }

                fileToProcess = conversionResult.getConvertedFile();
                conversionInfo = String.format("Converti depuis PDF (%dx%d, %d pages)",
                        conversionResult.getImageWidth(),
                        conversionResult.getImageHeight(),
                        conversionResult.getPdfPageCount());

                // Reset cache car fichier converti
                cachedBytes = null;
                hasCachedBytes = false;

                log.info("Conversion PDF réussie - {} → {} ({}x{})",
                        originalFile.getFilename(),
                        fileToProcess.getFilename(),
                        conversionResult.getImageWidth(),
                        conversionResult.getImageHeight());
            }

            // Sauvegarde avec cache ou normale
            FileStorageResult result;
            if (openKMEnabled) {
                if (hasCachedBytes) {
                    log.debug("Utilisation cache bytes pour sauvegarde OpenKM");
                    result = saveFileToOpenKMWithCachedBytes(originalFile, cachedBytes, sessionId, fileType);
                } else {
                    log.debug("Sauvegarde OpenKM normale");
                    result = saveFileToOpenKM(fileToProcess, sessionId, fileType);
                }
            } else {
                String subDirectory = fileType == FileType.IDENTITY_DOCUMENT ? "identity_documents" : "user_photos";
                if (hasCachedBytes) {
                    log.debug("Utilisation cache bytes pour sauvegarde locale");
                    result = saveFileLocallyWithCachedBytes(originalFile, cachedBytes, sessionId, fileType, subDirectory);
                } else {
                    log.debug("Sauvegarde locale normale");
                    result = saveFileLocally(fileToProcess, sessionId, fileType, subDirectory);
                }
            }

            // Ajout informations de conversion aux métadonnées
            if (result.isSuccess() && conversionInfo != null) {
                VerificationFile verificationFile = fileRepository.findById(result.getFileId()).orElse(null);
                if (verificationFile != null) {
                    String updatedFilename = String.format("%s [%s]",
                            verificationFile.getOriginalFilename(), conversionInfo);
                    verificationFile.setOriginalFilename(updatedFilename);
                    fileRepository.update(verificationFile);
                    log.debug("Métadonnées de conversion ajoutées: {}", conversionInfo);
                }
            }

            return result;

        } catch (Exception e) {
            log.error("Erreur sauvegarde fichier avec support PDF - Session: {}, Fichier: {}",
                    sessionId, originalFile.getFilename(), e);
            throw new IOException("Erreur sauvegarde: " + e.getMessage(), e);
        } finally {
            //Nettoyer le cache dans tous les cas
            fileValidator.clearCache(originalFile);
        }
    }

    //Sauvegarde OpenKM avec bytes cachés
    private FileStorageResult saveFileToOpenKMWithCachedBytes(CompletedFileUpload originalFile, byte[] cachedBytes,
                                                              String sessionId, FileType fileType) throws IOException {
        try {
            log.debug("Upload vers OpenKM  - Session: {}, Type: {}, Fichier: {}",
                    sessionId, fileType, originalFile.getFilename());

            // Créer un wrapper qui utilise les bytes cachés
            CachedBytesFileUpload cachedFile = new CachedBytesFileUpload(originalFile, cachedBytes);

            // Upload vers OpenKM avec le wrapper
            OpenKMService.OpenKMUploadResult uploadResult;
            if (fileType == FileType.IDENTITY_DOCUMENT) {
                uploadResult = openKMService.uploadIdentityDocument(cachedFile);
            } else {
                uploadResult = openKMService.uploadUserPhoto(cachedFile);
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

            log.info("Fichier sauvegardé avec succès dans OpenKM- Session: {}, UUID: {}, Taille: {} bytes",
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

    // Sauvegarde locale avec bytes cachés
    private FileStorageResult saveFileLocallyWithCachedBytes(CompletedFileUpload originalFile, byte[] cachedBytes,
                                                             String sessionId, FileType fileType, String subDirectory) throws IOException {
        try {
            log.debug("Sauvegarde locale - Session: {}, Type: {}, Dossier: {}",
                    sessionId, fileType, subDirectory);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = timestamp + "_" + UUID.randomUUID().toString() + "_" + originalFile.getFilename();

            Path directory = Paths.get(localBasePath, subDirectory);
            Path filePath = directory.resolve(filename);

            // Utiliser les bytes cachés au lieu de l'InputStream
            Files.write(filePath, cachedBytes);

            // Sauvegarder les métadonnées en PostgreSQL
            VerificationFile verificationFile = new VerificationFile(
                    sessionId,
                    fileType,
                    UUID.randomUUID().toString(),
                    filePath.toString(),
                    subDirectory
            );

            verificationFile.setOriginalFilename(originalFile.getFilename());
            verificationFile.setContentType(originalFile.getContentType().map(MediaType::toString).orElse("application/octet-stream"));
            verificationFile.setFileSize((long) cachedBytes.length);

            verificationFile = fileRepository.save(verificationFile);

            log.info("Fichier sauvegardé localement - Session: {}, Chemin: {}, Taille: {} bytes",
                    sessionId, filePath, cachedBytes.length);

            return FileStorageResult.success(
                    verificationFile.getId(),
                    verificationFile.getOpenkmUuid(),
                    filePath.toString(),
                    originalFile.getFilename()
            );

        } catch (Exception e) {
            log.error("Erreur sauvegarde locale pour session {}: {}", sessionId, e.getMessage(), e);
            throw new IOException("Erreur sauvegarde locale: " + e.getMessage(), e);
        }
    }

    // Bytes pour les chargements et l'extraction
    private static class CachedBytesFileUpload implements CompletedFileUpload {
        private final CompletedFileUpload original;
        private final byte[] cachedBytes;

        public CachedBytesFileUpload(CompletedFileUpload original, byte[] cachedBytes) {
            this.original = original;
            this.cachedBytes = cachedBytes;
        }

        @Override
        public byte[] getBytes() throws IOException {
            return cachedBytes;
        }

        @Override
        public java.io.InputStream getInputStream() throws IOException {
            return new java.io.ByteArrayInputStream(cachedBytes);
        }

        @Override
        public String getFilename() {
            return original.getFilename();
        }

        @Override
        public java.util.Optional<io.micronaut.http.MediaType> getContentType() {
            return original.getContentType();
        }

        public java.nio.ByteBuffer getByteBuffer() throws IOException {
            return java.nio.ByteBuffer.wrap(cachedBytes);
        }

        public long getSize() {
            return cachedBytes.length;
        }

        public long getDefinedSize() {
            return cachedBytes.length;
        }

        public boolean isEmpty() {
            return cachedBytes.length == 0;
        }

        public String getName() {
            return original.getName();
        }

        public boolean isComplete() {
            return true;
        }

        public void discard() {
            original.discard();
        }

        public void transferTo(String location) throws IOException {
            Files.write(Paths.get(location), cachedBytes);
        }

        public void transferTo(java.io.File dest) throws IOException {
            Files.write(dest.toPath(), cachedBytes);
        }

        public void transferTo(java.nio.file.Path dest) throws IOException {
            Files.write(dest, cachedBytes);
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
            // Mode local - retourne  le chemin
            log.debug("Mode local - Retour du chemin: {}", verificationFile.getOpenkmPath());
            return verificationFile.getOpenkmPath(); // on stocke le chemin local ici
        }
    }

    // NETTOYAGE ENRICHI
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

        //  Nettoyer les fichiers convertis expirés
        pdfConversionService.cleanupConvertedFiles();
    }

    // MÉTHODES PRIVÉES

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

    // MÉTHODES UTILITAIRES

    public boolean deleteFile(UUID fileId) {
        try {
            log.debug("Suppression fichier: {}", fileId);

            VerificationFile verificationFile = fileRepository.findById(fileId).orElse(null);
            if (verificationFile == null) {
                log.warn("Fichier non trouvé pour suppression: {}", fileId);
                return false;
            }

            if (openKMEnabled) {
                // on supprime juste l'enregistrement
                log.debug(" suppression de l'enregistrement ");
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