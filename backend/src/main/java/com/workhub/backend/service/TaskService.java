package com.workhub.backend.service;

import com.workhub.backend.dto.CreateTaskRequest;
import com.workhub.backend.dto.TaskResponse;
import com.workhub.backend.dto.UpdateTaskStatusRequest;
import com.workhub.backend.entity.Project;
import com.workhub.backend.entity.Task;
import com.workhub.backend.entity.TaskStatus;
import com.workhub.backend.exception.ResourceNotFoundException;
import com.workhub.backend.repository.ProjectRepository;
import com.workhub.backend.repository.TaskRepository;
import com.workhub.backend.repository.TenantRepository;
import com.workhub.backend.repository.UserRepository;
import com.workhub.backend.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;

    public TaskService(TaskRepository taskRepository,
                       ProjectRepository projectRepository,
                       TenantRepository tenantRepository,
                       UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
    }

    /**
     * Creates a task under the given project.
     *
     * ROLLBACK SCENARIO:
     * If the project does not exist for this tenant, it is auto-created inside this
     * transaction (Step 1). The task title is then validated inside the same transaction
     * (Step 2). If the title is blank, an exception is thrown — Spring rolls back the
     * entire transaction, including the auto-created project. Neither the project nor
     * the task will exist in the DB after the rollback.
     */
    @Transactional
    public TaskResponse createTask(UUID userId, UUID projectId, CreateTaskRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        // Step 1: find existing project or auto-create one within this transaction
        Project project = projectRepository.findByIdAndTenant_Id(projectId, tenantId)
                .orElseGet(() -> {
                    long count = projectRepository.countByTenant_IdAndNameStartingWith(
                            tenantId, "Auto-created project");
                    String name = "Auto-created project " + (count + 1);
                    Project autoCreated = Project.builder()
                            .tenant(tenantRepository.getReferenceById(tenantId))
                            .name(name)
                            .createdBy(userRepository.getReferenceById(userId))
                            .build();
                    return projectRepository.save(autoCreated); // flushed but not yet committed
                });

        // Step 2: validate task title inside the transaction so a failure rolls back Step 1
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("Task title must not be blank");
        }

        // Step 3: create the task
        Task task = Task.builder()
                .tenant(tenantRepository.getReferenceById(tenantId))
                .project(project)
                .title(request.getTitle())
                .status(TaskStatus.TODO)
                .build();

        return toResponse(taskRepository.save(task));
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> getTasksForProject(UUID projectId) {
        UUID tenantId = TenantContext.getTenantId();

        projectRepository.findByIdAndTenant_Id(projectId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        return taskRepository.findAllByProject_IdAndTenant_Id(projectId, tenantId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse getTask(UUID taskId) {
        return taskRepository.findByIdAndTenant_Id(taskId, TenantContext.getTenantId())
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
    }

    @Transactional
    public void deleteTask(UUID taskId) {
        Task task = taskRepository.findByIdAndTenant_Id(taskId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        taskRepository.delete(task);
    }

    @Transactional
    public TaskResponse updateTaskStatus(UUID taskId, UpdateTaskStatusRequest request) {
        Task task = taskRepository.findByIdAndTenant_Id(taskId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Task not found"));

        task.setStatus(request.getStatus());
        return toResponse(task);
    }

    private TaskResponse toResponse(Task task) {
        return TaskResponse.builder()
                .id(task.getId())
                .title(task.getTitle())
                .status(task.getStatus().name())
                .projectId(task.getProject().getId())
                .tenantId(task.getTenant().getId())
                .createdAt(task.getCreatedAt())
                .build();
    }
}
