package com.meetingintelligence.infrastructure.persistence.repository;

import com.meetingintelligence.domain.entity.OrganizationAiConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationAiConfigRepository extends JpaRepository<OrganizationAiConfig, UUID> {

    Optional<OrganizationAiConfig> findByOrganizationId(UUID organizationId);
}

