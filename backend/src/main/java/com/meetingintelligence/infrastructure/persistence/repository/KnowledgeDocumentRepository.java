package com.meetingintelligence.infrastructure.persistence.repository;

import com.meetingintelligence.domain.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, UUID> {
    List<KnowledgeDocument> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    List<KnowledgeDocument> findByOrganizationIdAndActiveTrue(UUID organizationId);

    Optional<KnowledgeDocument> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
