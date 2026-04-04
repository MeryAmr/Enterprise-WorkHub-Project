package com.workhub.backend.repository;

import com.workhub.backend.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {
    List<Task> findAllByProject_IdAndTenant_Id(UUID projectId, UUID tenantId);
    Optional<Task> findByIdAndTenant_Id(UUID id, UUID tenantId);
}
