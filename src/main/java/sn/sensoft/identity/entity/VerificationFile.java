package sn.sensoft.identity.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "verification_files")
public class VerificationFile {

    @Id
    @Column(columnDefinition = "uuid")
    @GeneratedValue(generator = "UUID")
    @org.hibernate.annotations.GenericGenerator(
            name = "UUID",
            strategy = "org.hibernate.id.UUIDGenerator"
    )
    private UUID id;

    @Column(name = "session_id", nullable = false, length = 8)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false)
    private FileType fileType;

    // Références OpenKM
    @Column(name = "openkm_uuid", nullable = false)
    private String openkmUuid;

    @Column(name = "openkm_path", nullable = false, length = 500)
    private String openkmPath;

    @Column(name = "openkm_folder", nullable = false)
    private String openkmFolder;

    // Métadonnées du fichier
    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    // Chemin temporaire pour scripts Python
    @Column(name = "temp_path", length = 500)
    private String tempPath;

    @Column(name = "temp_expires_at")
    private LocalDateTime tempExpiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // Constructeurs
    public VerificationFile() {
        this.createdAt = LocalDateTime.now();
    }

    public VerificationFile(String sessionId, FileType fileType, String openkmUuid,
                            String openkmPath, String openkmFolder) {
        this();
        this.sessionId = sessionId;
        this.fileType = fileType;
        this.openkmUuid = openkmUuid;
        this.openkmPath = openkmPath;
        this.openkmFolder = openkmFolder;
    }

    // Méthodes business
    public boolean isTempFileExpired() {
        return tempExpiresAt != null && LocalDateTime.now().isAfter(tempExpiresAt);
    }

    public void setTempFile(String tempPath, int expirationMinutes) {
        this.tempPath = tempPath;
        this.tempExpiresAt = LocalDateTime.now().plusMinutes(expirationMinutes);
    }

    public void clearTempFile() {
        this.tempPath = null;
        this.tempExpiresAt = null;
    }

    // Getters & Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public FileType getFileType() {
        return fileType;
    }

    public void setFileType(FileType fileType) {
        this.fileType = fileType;
    }

    public String getOpenkmUuid() {
        return openkmUuid;
    }

    public void setOpenkmUuid(String openkmUuid) {
        this.openkmUuid = openkmUuid;
    }

    public String getOpenkmPath() {
        return openkmPath;
    }

    public void setOpenkmPath(String openkmPath) {
        this.openkmPath = openkmPath;
    }

    public String getOpenkmFolder() {
        return openkmFolder;
    }

    public void setOpenkmFolder(String openkmFolder) {
        this.openkmFolder = openkmFolder;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getTempPath() {
        return tempPath;
    }

    public void setTempPath(String tempPath) {
        this.tempPath = tempPath;
    }

    public LocalDateTime getTempExpiresAt() {
        return tempExpiresAt;
    }

    public void setTempExpiresAt(LocalDateTime tempExpiresAt) {
        this.tempExpiresAt = tempExpiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}