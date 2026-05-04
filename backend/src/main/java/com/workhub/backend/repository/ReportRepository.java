package com.workhub.backend.repository;

import com.workhub.backend.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {
    Optional<Report> findByIdAndTenant_Id(UUID id, UUID tenantId);
    List<Report> findAllByTenant_Id(UUID tenantId);
}
