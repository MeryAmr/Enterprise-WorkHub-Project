package com.workhub.backend.repository;

import com.workhub.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.tenant WHERE u.email = :email")
    Optional<User> findByEmail(@Param("email") String email);

    @Query("SELECT u FROM User u JOIN FETCH u.tenant WHERE u.tenant.id = :tenantId")
    List<User> findAllByTenant_Id(@Param("tenantId") UUID tenantId);

    boolean existsByEmail(String email);

    @Query("SELECT u FROM User u JOIN FETCH u.tenant WHERE u.id = :id AND u.tenant.id = :tenantId")
    Optional<User> findByIdAndTenant_Id(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
