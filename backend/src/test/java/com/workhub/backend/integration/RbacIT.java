package com.workhub.backend.integration;

import com.workhub.backend.entity.Plan;
import com.workhub.backend.entity.Role;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.messaging.ReportProducer;
import com.workhub.backend.repository.TenantRepository;
import com.workhub.backend.repository.UserRepository;
import com.workhub.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class RbacIT {

    @Autowired MockMvc          mockMvc;
    @Autowired JwtService       jwtService;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository   userRepository;

    @MockitoBean ReportProducer reportProducer;

    String tokenAdmin;
    String tokenUser;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Tenant tenant = tenantRepository.save(
                Tenant.builder().name("RBAC Tenant " + suffix).plan(Plan.FREE).build());

        User admin = userRepository.save(User.builder()
                .email("rbac-admin-" + suffix + "@test.com")
                .passwordHash("irrelevant")
                .role(Role.TENANT_ADMIN)
                .tenant(tenant)
                .build());

        User user = userRepository.save(User.builder()
                .email("rbac-user-" + suffix + "@test.com")
                .passwordHash("irrelevant")
                .role(Role.TENANT_USER)
                .tenant(tenant)
                .build());

        tokenAdmin = jwtService.generateToken(admin);
        tokenUser  = jwtService.generateToken(user);
    }

    // Test 1: no token → 401
    @Test
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/projects"))
                .andExpect(status().isUnauthorized());
    }

    // Test 2: TENANT_USER on admin-only endpoint → 403
    @Test
    void wrongRole_tenantUserCreatesProject_returns403() throws Exception {
        mockMvc.perform(post("/projects")
                        .header("Authorization", "Bearer " + tokenUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unauthorized Project\"}"))
                .andExpect(status().isForbidden());
    }

    // Test 3: TENANT_ADMIN on admin-only endpoint → 201
    @Test
    void adminRole_tenantAdminCreatesProject_returns201() throws Exception {
        mockMvc.perform(post("/projects")
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Admin Project\"}"))
                .andExpect(status().isCreated());
    }
}
