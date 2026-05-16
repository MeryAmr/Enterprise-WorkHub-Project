package com.workhub.backend.service;

import com.workhub.backend.dto.CreateTaskRequest;
import com.workhub.backend.dto.TaskResponse;
import com.workhub.backend.dto.UpdateTaskStatusRequest;
import com.workhub.backend.entity.Plan;
import com.workhub.backend.entity.Project;
import com.workhub.backend.entity.Task;
import com.workhub.backend.entity.TaskStatus;
import com.workhub.backend.entity.Tenant;
import com.workhub.backend.entity.User;
import com.workhub.backend.exception.ResourceNotFoundException;
import com.workhub.backend.repository.ProjectRepository;
import com.workhub.backend.repository.TaskRepository;
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
class TaskServiceTest {

    @Mock TaskRepository    taskRepository;
    @Mock ProjectRepository projectRepository;
    @Mock TenantRepository  tenantRepository;
    @Mock UserRepository    userRepository;
    @InjectMocks TaskService taskService;

    static final UUID TENANT_ID  = UUID.randomUUID();
    static final UUID USER_ID    = UUID.randomUUID();
    static final UUID PROJECT_ID = UUID.randomUUID();
    static final UUID TASK_ID    = UUID.randomUUID();

    Tenant  tenant;
    Project project;
    Task    task;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        tenant  = Tenant.builder().id(TENANT_ID).name("Acme Corp").plan(Plan.FREE).build();
        User creator = User.builder().id(USER_ID).email("admin@acme.com").build();
        project = Project.builder().id(PROJECT_ID).tenant(tenant).name("Test Project").createdBy(creator).build();
        task    = Task.builder().id(TASK_ID).title("Test Task").project(project).tenant(tenant).build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── createTask ────────────────────────────────────────────────────────────

    @Test
    void createTask_existingProject_success() {
        CreateTaskRequest request = mock(CreateTaskRequest.class);
        when(request.getTitle()).thenReturn("New Task");
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.of(project));
        when(tenantRepository.getReferenceById(TENANT_ID)).thenReturn(tenant);
        Task saved = Task.builder().id(TASK_ID).title("New Task").project(project).tenant(tenant).build();
        when(taskRepository.save(any())).thenReturn(saved);

        TaskResponse response = taskService.createTask(USER_ID, PROJECT_ID, request);

        assertThat(response.getTitle()).isEqualTo("New Task");
        assertThat(response.getStatus()).isEqualTo("TODO");
        assertThat(response.getProjectId()).isEqualTo(PROJECT_ID);
        verify(taskRepository).save(any());
    }

    @Test
    void createTask_projectMissing_autoCreatesProject() {
        CreateTaskRequest request = mock(CreateTaskRequest.class);
        when(request.getTitle()).thenReturn("New Task");
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.empty());
        when(projectRepository.countByTenant_IdAndNameStartingWith(TENANT_ID, "Auto-created project")).thenReturn(0L);
        when(tenantRepository.getReferenceById(TENANT_ID)).thenReturn(tenant);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        Project autoProject = Project.builder().id(UUID.randomUUID()).tenant(tenant).name("Auto-created project 1").createdBy(mock(User.class)).build();
        when(projectRepository.save(any())).thenReturn(autoProject);
        Task saved = Task.builder().id(TASK_ID).title("New Task").project(autoProject).tenant(tenant).build();
        when(taskRepository.save(any())).thenReturn(saved);

        TaskResponse response = taskService.createTask(USER_ID, PROJECT_ID, request);

        assertThat(response.getTitle()).isEqualTo("New Task");
        verify(projectRepository).save(any());
        verify(taskRepository).save(any());
    }

    @Test
    void createTask_blankTitle_throwsAndTaskNotSaved() {
        CreateTaskRequest request = mock(CreateTaskRequest.class);
        when(request.getTitle()).thenReturn("");
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.empty());
        when(projectRepository.countByTenant_IdAndNameStartingWith(TENANT_ID, "Auto-created project")).thenReturn(0L);
        when(tenantRepository.getReferenceById(TENANT_ID)).thenReturn(tenant);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        Project autoProject = Project.builder().id(UUID.randomUUID()).tenant(tenant).name("Auto-created project 1").createdBy(mock(User.class)).build();
        when(projectRepository.save(any())).thenReturn(autoProject);

        assertThatThrownBy(() -> taskService.createTask(USER_ID, PROJECT_ID, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        verify(taskRepository, never()).save(any());
    }

    // ── getTasksForProject ────────────────────────────────────────────────────

    @Test
    void getTasksForProject_returnsList() {
        Task t2 = Task.builder().id(UUID.randomUUID()).title("Task 2").project(project).tenant(tenant).build();
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.of(project));
        when(taskRepository.findAllByProject_IdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(List.of(task, t2));

        List<TaskResponse> result = taskService.getTasksForProject(PROJECT_ID);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TaskResponse::getTitle)
                .containsExactlyInAnyOrder("Test Task", "Task 2");
    }

    @Test
    void getTasksForProject_projectNotFound_throwsResourceNotFoundException() {
        when(projectRepository.findByIdAndTenant_Id(PROJECT_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.getTasksForProject(PROJECT_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Project not found");
    }

    // ── getTask ───────────────────────────────────────────────────────────────

    @Test
    void getTask_success() {
        when(taskRepository.findByIdAndTenant_Id(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));

        TaskResponse response = taskService.getTask(TASK_ID);

        assertThat(response.getId()).isEqualTo(TASK_ID);
        assertThat(response.getTitle()).isEqualTo("Test Task");
    }

    @Test
    void getTask_notFound_throwsResourceNotFoundException() {
        when(taskRepository.findByIdAndTenant_Id(TASK_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.getTask(TASK_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Task not found");
    }

    // ── deleteTask ────────────────────────────────────────────────────────────

    @Test
    void deleteTask_success() {
        when(taskRepository.findByIdAndTenant_Id(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));

        taskService.deleteTask(TASK_ID);

        verify(taskRepository).delete(task);
    }

    @Test
    void deleteTask_notFound_throwsResourceNotFoundException() {
        when(taskRepository.findByIdAndTenant_Id(TASK_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.deleteTask(TASK_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── updateTaskStatus ──────────────────────────────────────────────────────

    @Test
    void updateTaskStatus_success() {
        when(taskRepository.findByIdAndTenant_Id(TASK_ID, TENANT_ID)).thenReturn(Optional.of(task));
        UpdateTaskStatusRequest request = mock(UpdateTaskStatusRequest.class);
        when(request.getStatus()).thenReturn(TaskStatus.IN_PROGRESS);

        TaskResponse response = taskService.updateTaskStatus(TASK_ID, request);

        assertThat(response.getStatus()).isEqualTo("IN_PROGRESS");
        verify(taskRepository).save(task);
    }

    @Test
    void updateTaskStatus_notFound_throwsResourceNotFoundException() {
        when(taskRepository.findByIdAndTenant_Id(TASK_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.updateTaskStatus(TASK_ID, mock(UpdateTaskStatusRequest.class)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
