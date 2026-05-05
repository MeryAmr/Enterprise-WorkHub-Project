package com.workhub.backend.service;

import com.workhub.backend.dto.UserResponse;
import com.workhub.backend.entity.Plan;
import com.workhub.backend.entity.Role;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.repository.UserRepository;
import com.workhub.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository  userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService      jwtService;
    @InjectMocks AuthService authService;

    static final UUID USER_ID   = UUID.randomUUID();
    static final UUID TENANT_ID = UUID.randomUUID();

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    void login_success() {
        Tenant tenant = Tenant.builder().id(TENANT_ID).name("Acme").plan(Plan.FREE).build();
        User user = User.builder().id(USER_ID).email("admin@acme.com").passwordHash("hashed")
                .tenant(tenant).role(Role.TENANT_ADMIN).build();
        when(userRepository.findByEmail("admin@acme.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        String token = authService.login("admin@acme.com", "password123");

        assertThat(token).isEqualTo("jwt-token");
    }

    @Test
    void login_userNotFound_throwsBadCredentialsException() {
        when(userRepository.findByEmail("unknown@acme.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("unknown@acme.com", "password"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void login_wrongPassword_throwsBadCredentialsException() {
        User user = User.builder().id(USER_ID).email("admin@acme.com").passwordHash("hashed").build();
        when(userRepository.findByEmail("admin@acme.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("admin@acme.com", "wrong"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid email or password");
    }

    // ── getCurrentUser ────────────────────────────────────────────────────────

    @Test
    void getCurrentUser_success() {
        Tenant tenant = Tenant.builder().id(TENANT_ID).name("Acme Corp").plan(Plan.FREE).build();
        User user = User.builder().id(USER_ID).email("admin@acme.com")
                .tenant(tenant).role(Role.TENANT_ADMIN).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        UserResponse response = authService.getCurrentUser(USER_ID);

        assertThat(response.getId()).isEqualTo(USER_ID);
        assertThat(response.getEmail()).isEqualTo("admin@acme.com");
        assertThat(response.getRole()).isEqualTo("TENANT_ADMIN");
        assertThat(response.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(response.getTenantName()).isEqualTo("Acme Corp");
    }

    @Test
    void getCurrentUser_notFound_throwsRuntimeException() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.getCurrentUser(USER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }
}
