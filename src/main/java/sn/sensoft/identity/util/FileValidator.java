package sn.sensoft.identity.util;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.io.ByteArrayInputStream;
import java.util.Map;
@Singleton
public class FileValidator {

    private static final Logger log = LoggerFactory.getLogger(FileValidator.class);

    private final Set<String> allowedExtensions;
    private final long maxFileSize;

    //Cache pour éviter double lecture des bytes
    private final Map<String, byte[]> bytesCache = new ConcurrentHashMap<>();

    //Types MIME supportés incluant PDF
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp",
            "application/pdf"
    );

    public FileValidator(@Value("${app.file-storage.allowed-extensions}") String allowedExtensions,
                         @Value("${app.file-storage.max-file-size}") String maxFileSize) {
        this.allowedExtensions = Set.of(allowedExtensions.toLowerCase().split(","));
        this.maxFileSize = parseFileSize(maxFileSize);
        log.info("FileValidator initialisé - Extensions: {}, Taille max: {}", this.allowedExtensions, formatFileSize(this.maxFileSize));
    }

    public boolean isValidFile(CompletedFileUpload file) {
        return isValidExtension(file) && isValidSize(file) && isValidMimeType(file);
    }

    public boolean isValidExtension(CompletedFileUpload file) {
        String filename = file.getFilename();
        if (filename == null || !filename.contains(".")) {
            return false;
        }

        String extension = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        return allowedExtensions.contains(extension);
    }

    public boolean isValidSize(CompletedFileUpload file) {
        return file.getSize() <= maxFileSize && file.getSize() > 0;
    }

    //Validation type MIME
    public boolean isValidMimeType(CompletedFileUpload file) {
        String mimeType = file.getContentType().map(mediaType -> mediaType.toString()).orElse("").toLowerCase();
        return ALLOWED_MIME_TYPES.contains(mimeType);
    }

    // Détection PDF
    public boolean isPDF(CompletedFileUpload file) {
        String mimeType = file.getContentType().map(mediaType -> mediaType.toString()).orElse("").toLowerCase();
        String filename = file.getFilename();

        // Vérification par MIME type ET extension
        boolean isMimePdf = "application/pdf".equals(mimeType);
        boolean isExtensionPdf = filename != null && filename.toLowerCase().endsWith(".pdf");

        log.debug("Détection PDF - Fichier: {}, MIME: {}, Extension PDF: {}", filename, mimeType, isExtensionPdf);

        return isMimePdf || isExtensionPdf;
    }

    // Détection Image
    public boolean isImage(CompletedFileUpload file) {
        String mimeType = file.getContentType().map(mediaType -> mediaType.toString()).orElse("").toLowerCase();
        return mimeType.startsWith("image/");
    }

    public String getValidationError(CompletedFileUpload file) {
        if (file == null) {
            return "Fichier manquant";
        }

        if (!isValidSize(file)) {
            return "Fichier trop volumineux ou vide. Taille maximum: " + formatFileSize(maxFileSize);
        }

        if (!isValidExtension(file)) {
            return "Extension de fichier non autorisée. Extensions acceptées: " + allowedExtensions;
        }

        if (!isValidMimeType(file)) {
            return "Type de fichier non supporté. Types acceptés: images (JPG, PNG) et PDF";
        }

        //Validation avancée avec cache pour images
        if (isImage(file)) {
            try {
                String cacheKey = generateCacheKey(file);

                // Lire les bytes et les mettre en cache
                byte[] cachedBytes = file.getBytes();
                bytesCache.put(cacheKey, cachedBytes);

                ValidationResult imageValidation = validateImageContentWithCachedBytes(
                        cachedBytes, file.getFilename());

                if (!imageValidation.isValid()) {
                    return imageValidation.getErrorMessage();
                }

            } catch (Exception e) {
                return "Erreur validation image: " + e.getMessage();
            }
        }

        return null; // Fichier valide
    }

    // Méthode pour compatibilité FileStorageService
    public ValidationResult validateFile(CompletedFileUpload file, long maxSize) {
        if (!isValidExtension(file)) {
            return ValidationResult.invalid("Extension de fichier non autorisée. Extensions acceptées: " + allowedExtensions);
        }

        if (file.getSize() > maxSize) {
            return ValidationResult.invalid("Fichier trop volumineux. Taille maximum: " + formatFileSize(maxSize));
        }

        if (file.getSize() == 0) {
            return ValidationResult.invalid("Fichier vide");
        }

        return ValidationResult.valid();
    }

    // Méthodes utilitaires
    private long parseFileSize(String size) {
        size = size.toUpperCase();
        if (size.endsWith("MB")) {
            return Long.parseLong(size.replace("MB", "")) * 1024 * 1024;
        } else if (size.endsWith("KB")) {
            return Long.parseLong(size.replace("KB", "")) * 1024;
        }
        return Long.parseLong(size);
    }

    private String formatFileSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)) + "MB";
        } else if (bytes >= 1024) {
            return (bytes / 1024) + "KB";
        }
        return bytes + " bytes";
    }

    // Classe ValidationResult
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    //  Validation avec bytes déjà lus
    private ValidationResult validateImageContentWithCachedBytes(byte[] cachedBytes, String filename) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(cachedBytes));

            if (image == null) {
                return ValidationResult.invalid("Fichier image corrompu ou format non supporté");
            }

            // Dimensions minimales
            int minWidth = 200;
            int minHeight = 200;

            if (image.getWidth() < minWidth || image.getHeight() < minHeight) {
                return ValidationResult.invalid(
                        String.format("Image trop petite. Minimum requis: %dx%d pixels, trouvé: %dx%d",
                                minWidth, minHeight, image.getWidth(), image.getHeight())
                );
            }

            // Ratio d'aspect raisonnable
            double ratio = (double) image.getWidth() / image.getHeight();
            if (ratio < 0.3 || ratio > 3.0) {
                return ValidationResult.invalid(
                        String.format("Ratio d'image incorrect (%.2f). Ratio acceptable: 0.3-3.0", ratio)
                );
            }

            log.debug("Image validée avec succès (cache) - Fichier: {}, Dimensions: {}x{}, Ratio: {:.2f}",
                    filename, image.getWidth(), image.getHeight(), ratio);

            return ValidationResult.valid();

        } catch (Exception e) {
            log.error("Erreur validation image avec cache: {}", filename, e);
            return ValidationResult.invalid("Erreur validation image: " + e.getMessage());
        }
    }

    // Récupérer les bytes mis en cache
    public byte[] getCachedBytes(CompletedFileUpload file) {
        String cacheKey = generateCacheKey(file);
        return bytesCache.get(cacheKey);
    }

    // Nettoyer le cache après utilisation
    public void clearCache(CompletedFileUpload file) {
        String cacheKey = generateCacheKey(file);
        byte[] removed = bytesCache.remove(cacheKey);
        if (removed != null) {
            log.debug("Cache nettoyé pour fichier: {} ({} bytes)", file.getFilename(), removed.length);
        }
    }

    // Générer clé unique pour le cache
    private String generateCacheKey(CompletedFileUpload file) {
        return file.getFilename() + "_" + file.getSize() + "_" + System.identityHashCode(file);
    }
}