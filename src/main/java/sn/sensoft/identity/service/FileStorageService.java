package sn.sensoft.identity.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Singleton
public class FileStorageService {

    private final String basePath;

    public FileStorageService(@Value("${app.file-storage.base-path}") String basePath) {
        this.basePath = basePath;
        createDirectories();
    }

    public String saveFile(CompletedFileUpload file, String subDirectory) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = timestamp + "_" + UUID.randomUUID().toString() + "_" + file.getFilename();

        Path directory = Paths.get(basePath, subDirectory);
        Path filePath = directory.resolve(filename);

        Files.copy(file.getInputStream(), filePath);

        return filePath.toString();
    }

    public String saveIdentityDocument(CompletedFileUpload file) throws IOException {
        return saveFile(file, "identity_documents");
    }

    public String saveUserPhoto(CompletedFileUpload file) throws IOException {
        return saveFile(file, "user_photos");
    }

    public boolean deleteFile(String filePath) {
        try {
            return Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            return false;
        }
    }

    private void createDirectories() {
        try {
            Files.createDirectories(Paths.get(basePath, "identity_documents"));
            Files.createDirectories(Paths.get(basePath, "user_photos"));
        } catch (IOException e) {
            throw new RuntimeException("Impossible de créer les répertoires de stockage", e);
        }
    }
}