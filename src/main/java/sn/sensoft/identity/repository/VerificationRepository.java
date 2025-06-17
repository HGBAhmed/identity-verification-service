package sn.sensoft.identity.repository;

import sn.sensoft.identity.entity.VerificationRequest;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

@Repository
public interface VerificationRepository extends JpaRepository<VerificationRequest, UUID> {

    List<VerificationRequest> findByUserIdentifierOrderByCreatedAtDesc(String userIdentifier);

    List<VerificationRequest> findByStatus(String status);

    long countByUserIdentifier(String userIdentifier);
}