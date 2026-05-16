package com.workhub.backend.integration;

import com.workhub.backend.entity.Plan;
import com.workhub.backend.entity.Project;
import com.workhub.backend.entity.Role;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.messaging.ReportProducer;
import com.workhub.backend.repository.ProjectRepository;
import com.workhub.backend.repository.TenantRepository;
import com.workhub.backend.repository.UserRepository;
import com.workhub.backend.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// NO @Transactional on this class — we must observe the real committed/rolled-back DB state.
// If the test were @Transactional, the service would join the test's outer transaction
// (PROPAGATION_REQUIRED), the Hibernate first-level cache would still hold the auto-created
// project in memory, and the rollback assertion would be meaningless.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class TransactionRollbackIT {

    @Autowired MockMvc           mockMvc;
    @Autowired JwtService        jwtService;
    @Autowired TenantRepository  tenantRepository;
    @Autowired UserRepository    userRepository;
    @Autowired ProjectRepository projectRepository;

    @MockitoBean ReportProducer reportProducer;

    Tenant tenant;
    User   admin;
    String tokenAdmin;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        tenant = tenantRepository.save(
                Tenant.builder().name("Rollback Tenant " + suffix).plan(Plan.FREE).build());

        admin = userRepository.save(User.builder()
                .email("rollback-admin-" + suffix + "@test.com")
                .passwordHash("irrelevant")
                .role(Role.TENANT_ADMIN)
                .tenant(tenant)
                .build());

        tokenAdmin = jwtService.generateToken(admin);
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteById(admin.getId());
        tenantRepository.deleteById(tenant.getId());
    }

    @Test
    void createTask_blankTitle_rollsBackAutoCreatedProject() throws Exception {
        // TaskService.createTask() is @Transactional and performs two writes:
        //   Write 1: project not found → auto-creates a project (projectRepository.save)
        //   Write 2: (never reached) saves the task
        // Between them, it validates the title. A blank title throws IllegalArgumentException
        // midway → Spring rolls back the entire transaction including Write 1.
        // This test asserts that no partial write (the auto-created project) survives.

        UUID nonExistentProjectId = UUID.randomUUID();

        mockMvc.perform(post("/projects/{id}/tasks", nonExistentProjectId)
                        .header("Authorization", "Bearer " + tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest());

        // Assert: the auto-created project (Write 1) was rolled back — no partial write in DB
        List<Project> projects = projectRepository.findAllByTenant_Id(tenant.getId());
        assertThat(projects).isEmpty();
    }
}
