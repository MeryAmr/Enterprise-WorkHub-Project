package com.workhub.backend.service;

import com.workhub.backend.dto.CreateProjectRequest;
import com.workhub.backend.dto.ProjectResponse;
import com.workhub.backend.dto.UpdateProjectRequest;
import com.workhub.backend.entity.Plan;
import com.workhub.backend.entity.Project;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.exception.ResourceNotFoundException;
import com.workhub.backend.repository.ProjectRepository;
import com.workhub.backend.repository.TenantRepository;
import com.workhub.backend.repository.UserRepository;
import com.workhub.backend.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock ProjectRepository projectRepository;
    @Mock TenantRepository  tenantRepository;
    @Mock UserRepository    userRepository;
    @InjectMocks ProjectService projectService;

    static final UUID TENANT_ID  = UUID.randomUUID();
    static final UUID USER_ID    = UUID.randomUUID();
    static final UUID PROJECT_ID = UUID.randomUUID();

    Tenant  tenant;
    User    creator;
    Project project;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        tenant  = Tenant.builder().id(TENANT_ID).name("Acme Corp").plan(Plan.FREE).build();
        creator = User.builder().id(USER_ID).email("admin@acme.com").build();
        project = Project.builder().id(PROJECT_ID).tenant(tenant).name("Test Project").createdBy(creator).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── createProject ─────────────────────────────────────────────────────────

    @Test
    void createProject_success() {
        CreateProjectRequest request = mock(CreateProjectRequest.class);
        when(request.getName()).thenReturn("New Project");
        when(tenantRepository.getReferenceById(TENANT_ID)).thenReturn(tenant);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(creator);
        Project saved = Project.builder().id(UUID.randomUUID()).tenant(tenant).name("New Project").createdBy(creator).build();
        when(projectRepository.save(any())).thenReturn(saved);

        ProjectResponse response = projectService.createProject(USER_ID, request);

        assertThat(response.getName()).isEqualTo("New Project");
        assertThat(response.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(response.getCreatedBy()).isEqualTo(USER_ID);
        verify(projectRepository).save(any());
    }

    // ── getProjects ───────────────────────────────────────────────────────────

    @Test
    void getProjects_returnsList() {
        Project p2 = Project.builder().id(UUID.randomUUID()).tenant(tenant).name("Project 2").createdBy(creator).build();
        when(projectRepository.findAllByTenant_Id(TENANT_ID)).thenReturn(List.of(project, p2));

        List<ProjectResponse> result = projectService.getProjects();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ProjectResponse::getName)
                .containsExactlyInAnyOrder("Test Project", "Project 2");
    }

    // ── getProject ────────────────────────────────────────────────────────────

    @Test
    void getProject_success() {
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.of(project));

        ProjectResponse response = projectService.getProject(PROJECT_ID);

        assertThat(response.getId()).isEqualTo(PROJECT_ID);
        assertThat(response.getName()).isEqualTo("Test Project");
    }

    @Test
    void getProject_notFound_throwsResourceNotFoundException() {
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.getProject(PROJECT_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Project not found");
    }

    // ── updateProject ─────────────────────────────────────────────────────────

    @Test
    void updateProject_success() {
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.of(project));
        UpdateProjectRequest request = mock(UpdateProjectRequest.class);
        when(request.getName()).thenReturn("Updated Name");

        ProjectResponse response = projectService.updateProject(PROJECT_ID, request);

        assertThat(response.getName()).isEqualTo("Updated Name");
        verify(projectRepository).save(project);
    }

    @Test
    void updateProject_notFound_throwsResourceNotFoundException() {
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.updateProject(PROJECT_ID, mock(UpdateProjectRequest.class)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── deleteProject ─────────────────────────────────────────────────────────

    @Test
    void deleteProject_success() {
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.of(project));

        projectService.deleteProject(PROJECT_ID);

        verify(projectRepository).delete(project);
    }

    @Test
    void deleteProject_notFound_throwsResourceNotFoundException() {
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.deleteProject(PROJECT_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
