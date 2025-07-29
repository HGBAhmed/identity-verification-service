package sn.sensoft.identity.dto;

import io.micronaut.serde.annotation.Serdeable;
import sn.sensoft.identity.entity.FileType;

import java.time.LocalDateTime;
import java.util.UUID;

@Serdeable
public class FileInfoDto {

    private UUID id;
    private String sessionId;
    private FileType fileType;
    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private LocalDateTime createdAt;
    private String downloadUrl;

    // Constructeurs
    public FileInfoDto() {}

    public FileInfoDto(UUID id, String sessionId, FileType fileType, String originalFilename,
                       String contentType, Long fileSize, LocalDateTime createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.fileType = fileType;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.createdAt = createdAt;
        this.downloadUrl = "/api/v1/verification/files/" + id + "/download";
    }

    // Getters et Setters
    public UUID getId() { return id; }
    public void setId(UUID id) {
        this.id = id;
        this.downloadUrl = "/api/v1/verification/files/" + id + "/download";
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public FileType getFileType() { return fileType; }
    public void setFileType(FileType fileType) { this.fileType = fileType; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl) { this.downloadUrl = downloadUrl; }

    // Méthodes utilitaires
    public String getFormattedFileSize() {
        if (fileSize == null) return "0 B";

        if (fileSize < 1024) return fileSize + " B";
        if (fileSize < 1024 * 1024) return String.format("%.1f KB", fileSize / 1024.0);
        return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
    }

    public boolean isImage() {
        return contentType != null && contentType.startsWith("image/");
    }

    public boolean isPdf() {
        return "application/pdf".equals(contentType);
    }
}