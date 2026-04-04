package com.workhub.backend.repository;

import com.workhub.backend.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findAllByTenant_Id(UUID tenantId);
    Optional<Project> findByIdAndTenant_Id(UUID id, UUID tenantId);
    long countByTenant_IdAndNameStartingWith(UUID tenantId, String namePrefix);
}
