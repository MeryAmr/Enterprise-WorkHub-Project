package com.workhub.backend.controller;

import com.workhub.backend.dto.InviteUserRequest;
import com.workhub.backend.dto.UserResponse;
import com.workhub.backend.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/organization")
@PreAuthorize("hasAnyRole('TENANT_ADMIN','TENANT_USER')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/members")
    public ResponseEntity<List<UserResponse>> getMembers() {
        return ResponseEntity.ok(userService.getOrganizationMembers());
    }

    @PostMapping("/invite")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<UserResponse> inviteUser(@Valid @RequestBody InviteUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.inviteUser(request));
    }

    @DeleteMapping("/members/{userId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> kickUser(
            @PathVariable UUID userId,
            Authentication authentication) {
        UUID requestingUserId = (UUID) authentication.getPrincipal();
        userService.kickUser(userId, requestingUserId);
        return ResponseEntity.noContent().build();
    }
}
