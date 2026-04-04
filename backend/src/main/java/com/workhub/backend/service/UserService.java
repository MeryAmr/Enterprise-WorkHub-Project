package com.workhub.backend.service;

import com.workhub.backend.dto.InviteUserRequest;
import com.workhub.backend.dto.UserResponse;
import com.workhub.backend.entity.Role;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.exception.ResourceNotFoundException;
import com.workhub.backend.repository.TenantRepository;
import com.workhub.backend.repository.UserRepository;
import com.workhub.backend.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;

    public UserService(UserRepository userRepository, TenantRepository tenantRepository) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public UserResponse inviteUser(InviteUserRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("No registered user found with that email"));

        if (user.getTenant() != null && user.getTenant().getId().equals(tenantId)) {
            throw new IllegalArgumentException("User is already a member of this organization");
        }

        if (user.getTenant() != null && !user.getTenant().getId().equals(tenantId)) {
            throw new IllegalArgumentException("User already belongs to another organization");
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));

        user.setTenant(tenant);
        user.setRole(Role.TENANT_USER);

        return toResponse(user);
    }

    @Transactional
    public void kickUser(UUID targetUserId, UUID requestingUserId) {
        UUID tenantId = TenantContext.getTenantId();

        if (targetUserId.equals(requestingUserId)) {
            throw new IllegalArgumentException("You cannot remove yourself from the organization");
        }

        User target = userRepository.findByIdAndTenant_Id(targetUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found in this organization"));

        if (target.getRole() == Role.TENANT_ADMIN) {
            throw new IllegalArgumentException("Cannot remove another admin from the organization");
        }

        target.setTenant(null);
        target.setRole(null);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getOrganizationMembers() {
        return userRepository.findAllByTenant_Id(TenantContext.getTenantId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole().name())
                .tenantId(user.getTenant().getId())
                .tenantName(user.getTenant().getName())
                .build();
    }
}
