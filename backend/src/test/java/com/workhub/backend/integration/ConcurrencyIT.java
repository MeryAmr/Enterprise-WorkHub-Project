package com.workhub.backend.integration;

import com.workhub.backend.entity.Plan;
import com.workhub.backend.entity.Project;
import com.workhub.backend.entity.Role;
import com.workhub.backend.entity.Task;
import com.workhub.backend.entity.TaskStatus;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.messaging.ReportProducer;
import com.workhub.backend.repository.ProjectRepository;
import com.workhub.backend.repository.TaskRepository;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

// NO @Transactional — the final taskRepository.findById() must open a fresh transaction
// and hit the real DB. With @Transactional on the test class, that query would reuse the
// same outer transaction and read Hibernate's first-level cache, masking the true committed version.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ConcurrencyIT {

    @Autowired MockMvc           mockMvc;
    @Autowired JwtService        jwtService;
    @Autowired TenantRepository  tenantRepository;
    @Autowired UserRepository    userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired TaskRepository    taskRepository;

    @MockitoBean ReportProducer reportProducer;

    Tenant  tenant;
    User    admin;
    Project project;
    Task    task;
    String  tokenAdmin;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        tenant = tenantRepository.save(
                Tenant.builder().name("Concurrency Tenant " + suffix).plan(Plan.FREE).build());

        admin = userRepository.save(User.builder()
                .email("concurrency-admin-" + suffix + "@test.com")
                .passwordHash("irrelevant")
                .role(Role.TENANT_ADMIN)
                .tenant(tenant)
                .build());

        project = projectRepository.save(Project.builder()
                .name("Concurrency Project " + suffix)
                .tenant(tenant)
                .createdBy(admin)
                .build());

        task = taskRepository.save(Task.builder()
                .title("Concurrent Task")
                .status(TaskStatus.TODO)
                .project(project)
                .tenant(tenant)
                .build());

        tokenAdmin = jwtService.generateToken(admin);
    }

    @AfterEach
    void tearDown() {
        taskRepository.deleteById(task.getId());
        projectRepository.deleteById(project.getId());
        userRepository.deleteById(admin.getId());
        tenantRepository.deleteById(tenant.getId());
    }

    @Test
    void concurrentStatusUpdates_optimisticLockingPreventsLostUpdates() throws Exception {
        // Fire THREAD_COUNT simultaneous PATCH /tasks/{id} requests.
        // @Version on Task entity means only the first save at each version wins;
        // the rest get ObjectOptimisticLockingFailureException → HTTP 500.
        // Final task.version == number of 200 responses — proves no lost update.

        int THREAD_COUNT = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int status = mockMvc.perform(patch("/tasks/{id}", task.getId())
                                    .header("Authorization", "Bearer " + tokenAdmin)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"status\":\"IN_PROGRESS\"}"))
                            .andReturn().getResponse().getStatus();
                    statusCodes.add(status);
                } catch (Exception e) {
                    statusCodes.add(-1);
                }
            });
        }

        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        long successCount  = statusCodes.stream().filter(s -> s == 200).count();
        long conflictCount = statusCodes.stream().filter(s -> s == 500).count();

        assertThat(statusCodes).hasSize(THREAD_COUNT);
        assertThat(successCount).isGreaterThanOrEqualTo(1);
        assertThat(successCount + conflictCount).isEqualTo(THREAD_COUNT);

        Task finalTask = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(finalTask.getVersion()).isEqualTo(successCount);
    }
}
