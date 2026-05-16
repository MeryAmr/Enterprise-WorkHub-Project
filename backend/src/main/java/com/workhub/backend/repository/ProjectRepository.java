package com.workhub.backend.repository;

import com.workhub.backend.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    @Query("SELECT p FROM Project p JOIN FETCH p.tenant JOIN FETCH p.createdBy WHERE p.tenant.id = :tenantId")
    List<Project> findAllByTenant_Id(@Param("tenantId") UUID tenantId);

    @Query("SELECT p FROM Project p JOIN FETCH p.tenant JOIN FETCH p.createdBy WHERE p.id = :id AND p.tenant.id = :tenantId")
    Optional<Project> findByIdAndTenant_Id(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    long countByTenant_IdAndNameStartingWith(UUID tenantId, String namePrefix);
}
