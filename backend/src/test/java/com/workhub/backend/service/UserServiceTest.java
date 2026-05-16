package com.workhub.backend.service;

import com.workhub.backend.dto.InviteUserRequest;
import com.workhub.backend.dto.UserResponse;
import com.workhub.backend.entity.Plan;
import com.workhub.backend.entity.Role;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.exception.ResourceNotFoundException;
import com.workhub.backend.repository.TenantRepository;
import com.workhub.backend.repository.UserRepository;
import com.workhub.backend.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock TenantRepository tenantRepository;
    @InjectMocks UserService userService;

    static final UUID TENANT_ID = UUID.randomUUID();
    static final UUID USER_ID   = UUID.randomUUID();
    static final UUID TARGET_ID = UUID.randomUUID();

    Tenant tenant;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        tenant = Tenant.builder().id(TENANT_ID).name("Acme Corp").plan(Plan.FREE).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── inviteUser ────────────────────────────────────────────────────────────

    @Test
    void inviteUser_success() {
        User user = User.builder().id(TARGET_ID).email("alice@acme.com").build();
        InviteUserRequest request = mock(InviteUserRequest.class);
        when(request.getEmail()).thenReturn("alice@acme.com");
        when(userRepository.findByEmail("alice@acme.com")).thenReturn(Optional.of(user));
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        UserResponse response = userService.inviteUser(request);

        assertThat(response.getEmail()).isEqualTo("alice@acme.com");
        assertThat(response.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(response.getRole()).isEqualTo("TENANT_USER");
        verify(userRepository).save(user);
    }

    @Test
    void inviteUser_userNotFound_throwsResourceNotFoundException() {
        InviteUserRequest request = mock(InviteUserRequest.class);
        when(request.getEmail()).thenReturn("unknown@acme.com");
        when(userRepository.findByEmail("unknown@acme.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.inviteUser(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No registered user");
    }

    @Test
    void inviteUser_alreadyMemberOfSameTenant_throwsIllegalArgumentException() {
        User user = User.builder().id(TARGET_ID).email("alice@acme.com").tenant(tenant).role(Role.TENANT_USER).build();
        InviteUserRequest request = mock(InviteUserRequest.class);
        when(request.getEmail()).thenReturn("alice@acme.com");
        when(userRepository.findByEmail("alice@acme.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.inviteUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already a member");
    }

    @Test
    void inviteUser_belongsToAnotherTenant_throwsIllegalArgumentException() {
        Tenant other = Tenant.builder().id(UUID.randomUUID()).name("Globex").plan(Plan.FREE).build();
        User user = User.builder().id(TARGET_ID).email("charlie@globex.com").tenant(other).role(Role.TENANT_USER).build();
        InviteUserRequest request = mock(InviteUserRequest.class);
        when(request.getEmail()).thenReturn("charlie@globex.com");
        when(userRepository.findByEmail("charlie@globex.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.inviteUser(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another organization");
    }

    // ── kickUser ──────────────────────────────────────────────────────────────

    @Test
    void kickUser_success() {
        User target = User.builder().id(TARGET_ID).email("alice@acme.com").tenant(tenant).role(Role.TENANT_USER).build();
        when(userRepository.findByIdAndTenant_Id(TARGET_ID, TENANT_ID)).thenReturn(Optional.of(target));

        userService.kickUser(TARGET_ID, USER_ID);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getTenant()).isNull();
        assertThat(captor.getValue().getRole()).isNull();
    }

    @Test
    void kickUser_selfKick_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> userService.kickUser(USER_ID, USER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot remove yourself");
    }

    @Test
    void kickUser_targetIsAdmin_throwsIllegalArgumentException() {
        User admin = User.builder().id(TARGET_ID).email("admin2@acme.com").tenant(tenant).role(Role.TENANT_ADMIN).build();
        when(userRepository.findByIdAndTenant_Id(TARGET_ID, TENANT_ID)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.kickUser(TARGET_ID, USER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot remove another admin");
    }

    @Test
    void kickUser_userNotFound_throwsResourceNotFoundException() {
        when(userRepository.findByIdAndTenant_Id(TARGET_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.kickUser(TARGET_ID, USER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── getOrganizationMembers ────────────────────────────────────────────────

    @Test
    void getOrganizationMembers_returnsList() {
        User u1 = User.builder().id(UUID.randomUUID()).email("alice@acme.com").tenant(tenant).role(Role.TENANT_USER).build();
        User u2 = User.builder().id(UUID.randomUUID()).email("bob@acme.com").tenant(tenant).role(Role.TENANT_USER).build();
        when(userRepository.findAllByTenant_Id(TENANT_ID)).thenReturn(List.of(u1, u2));

        List<UserResponse> result = userService.getOrganizationMembers();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(UserResponse::getEmail)
                .containsExactlyInAnyOrder("alice@acme.com", "bob@acme.com");
    }
}
