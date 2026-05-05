package com.workhub.backend.repository;

import com.workhub.backend.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {
    @Query("SELECT t FROM Task t JOIN FETCH t.project JOIN FETCH t.tenant WHERE t.project.id = :projectId AND t.tenant.id = :tenantId")
    List<Task> findAllByProject_IdAndTenant_Id(@Param("projectId") UUID projectId, @Param("tenantId") UUID tenantId);

    @Query("SELECT t FROM Task t JOIN FETCH t.project JOIN FETCH t.tenant WHERE t.id = :id AND t.tenant.id = :tenantId")
    Optional<Task> findByIdAndTenant_Id(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
