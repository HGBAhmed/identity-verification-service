package sn.sensoft.identity.repository;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import sn.sensoft.identity.entity.VerificationFile;
import sn.sensoft.identity.entity.FileType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationFileRepository extends JpaRepository<VerificationFile, UUID> {

    // Recherche par session
    List<VerificationFile> findBySessionId(String sessionId);

    // Recherche par session et type
    Optional<VerificationFile> findBySessionIdAndFileType(String sessionId, FileType fileType);

    // Recherche par UUID OpenKM
    Optional<VerificationFile> findByOpenkmUuid(String openkmUuid);

    // Fichiers temporaires expirés
    List<VerificationFile> findByTempExpiresAtBeforeAndTempPathIsNotNull(LocalDateTime now);

    // Nettoyage des fichiers temporaires expirés
    int deleteByTempExpiresAtBeforeAndTempPathIsNotNull(LocalDateTime now);

    // Fichiers par dossier OpenKM
    List<VerificationFile> findByOpenkmFolder(String openkmFolder);

    // Statistiques de stockage
    long countByFileType(FileType fileType);

    // Taille totale des fichiers
    @Query("SELECT COALESCE(SUM(f.fileSize), 0) FROM VerificationFile f WHERE f.fileType = :fileType")
    Optional<Long> sumFileSizeByFileType(FileType fileType);

    // à ajouter parce que micronaut ne le fait pas auto
    @Override
    VerificationFile update(VerificationFile file);
}
