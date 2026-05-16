package com.workhub.backend.integration;

import com.workhub.backend.entity.Plan;
import com.workhub.backend.entity.Project;
import com.workhub.backend.entity.Role;
import com.workhub.backend.entity.Task;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.messaging.ReportProducer;
import com.workhub.backend.repository.ProjectRepository;
import com.workhub.backend.repository.TaskRepository;
import com.workhub.backend.repository.TenantRepository;
import com.workhub.backend.repository.UserRepository;
import com.workhub.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class TenantIsolationIT {

    @Autowired MockMvc          mockMvc;
    @Autowired JwtService       jwtService;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository   userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired TaskRepository   taskRepository;

    @MockitoBean ReportProducer reportProducer;

    String  tokenA;
    Project projectB;
    Task    taskB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Tenant tenantA = tenantRepository.save(
                Tenant.builder().name("Tenant Alpha " + suffix).plan(Plan.FREE).build());
        Tenant tenantB = tenantRepository.save(
                Tenant.builder().name("Tenant Beta " + suffix).plan(Plan.FREE).build());

        User userA = userRepository.save(User.builder()
                .email("admin-a-" + suffix + "@test.com")
                .passwordHash("irrelevant")
                .role(Role.TENANT_ADMIN)
                .tenant(tenantA)
                .build());

        User userB = userRepository.save(User.builder()
                .email("admin-b-" + suffix + "@test.com")
                .passwordHash("irrelevant")
                .role(Role.TENANT_ADMIN)
                .tenant(tenantB)
                .build());

        tokenA = jwtService.generateToken(userA);

        projectB = projectRepository.save(Project.builder()
                .tenant(tenantB)
                .name("Project Beta " + suffix)
                .createdBy(userB)
                .build());

        taskB = taskRepository.save(Task.builder()
                .tenant(tenantB)
                .project(projectB)
                .title("Task Beta " + suffix)
                .build());
    }

    // Test 1: cross-tenant read — Tenant A JWT cannot read Tenant B's project
    @Test
    void crossTenantRead_tenantACannotReadTenantBProject() throws Exception {
        mockMvc.perform(get("/projects/{id}", projectB.getId())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    // Test 2: cross-tenant update — Tenant A JWT cannot update Tenant B's task
    @Test
    void crossTenantUpdate_tenantACannotUpdateTenantBTask() throws Exception {
        mockMvc.perform(patch("/tasks/{id}", taskB.getId())
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isNotFound());
    }

    // Test 3: cross-tenant list — Tenant A sees empty list; Tenant B's projects must not appear
    @Test
    void crossTenantList_tenantASeesNoProjects() throws Exception {
        mockMvc.perform(get("/projects")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}
