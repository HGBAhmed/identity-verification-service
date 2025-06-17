package sn.sensoft.identity.util;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Singleton;
import java.util.Set;

@Singleton
public class FileValidator {

    private final Set<String> allowedExtensions;
    private final long maxFileSize;

    public FileValidator(@Value("${app.file-storage.allowed-extensions}") String allowedExtensions,
                         @Value("${app.file-storage.max-file-size}") String maxFileSize) {
        this.allowedExtensions = Set.of(allowedExtensions.toLowerCase().split(","));
        this.maxFileSize = parseFileSize(maxFileSize);
    }

    public boolean isValidFile(CompletedFileUpload file) {
        return isValidExtension(file) && isValidSize(file);
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
        return file.getSize() <= maxFileSize;
    }

    public String getValidationError(CompletedFileUpload file) {
        if (!isValidExtension(file)) {
            return "Extension de fichier non autorisée. Extensions acceptées: " + allowedExtensions;
        }
        if (!isValidSize(file)) {
            return "Fichier trop volumineux. Taille maximum: " + formatFileSize(maxFileSize);
        }
        return null;
    }

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
}