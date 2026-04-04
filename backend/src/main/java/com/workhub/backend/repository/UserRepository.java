package com.workhub.backend.repository;

import com.workhub.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    List<User> findAllByTenant_Id(UUID tenantId);
    boolean existsByEmail(String email);
    Optional<User> findByIdAndTenant_Id(UUID id, UUID tenantId);
}
