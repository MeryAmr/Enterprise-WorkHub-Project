package com.workhub.backend.service;

import com.workhub.backend.dto.CreateTaskRequest;
import com.workhub.backend.dto.TaskResponse;
import com.workhub.backend.dto.UpdateTaskStatusRequest;
import com.workhub.backend.entity.Project;
import com.workhub.backend.entity.Task;
import com.workhub.backend.entity.TaskStatus;
import com.workhub.backend.entity.User;
import com.workhub.backend.exception.ResourceNotFoundException;
import com.workhub.backend.repository.ProjectRepository;
import com.workhub.backend.repository.TaskRepository;
import com.workhub.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public TaskService(TaskRepository taskRepository,
                       ProjectRepository projectRepository,
                       UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
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
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        UUID tenantId = user.getTenant().getId();

        // Step 1: find existing project or auto-create one within this transaction
        Project project = projectRepository.findByIdAndTenant_Id(projectId, tenantId)
                .orElseGet(() -> {
                    long count = projectRepository.countByTenant_IdAndNameStartingWith(
                            tenantId, "Auto-created project");
                    String name = "Auto-created project " + (count + 1);
                    Project autoCreated = Project.builder()
                            .tenant(user.getTenant())
                            .name(name)
                            .createdBy(user)
                            .build();
                    return projectRepository.save(autoCreated); // flushed but not yet committed
                });

        // Step 2: validate task title inside the transaction so a failure rolls back Step 1
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("Task title must not be blank");
        }

        // Step 3: create the task
        Task task = Task.builder()
                .tenant(user.getTenant())
                .project(project)
                .title(request.getTitle())
                .status(TaskStatus.TODO)
                .build();

        task = taskRepository.save(task);
        return toResponse(task);
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> getTasksForProject(UUID userId, UUID projectId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        UUID tenantId = user.getTenant().getId();

        // Verify the project belongs to this tenant
        projectRepository.findByIdAndTenant_Id(projectId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        return taskRepository.findAllByProject_IdAndTenant_Id(projectId, tenantId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TaskResponse updateTaskStatus(UUID userId, UUID taskId, UpdateTaskStatusRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Task task = taskRepository.findByIdAndTenant_Id(taskId, user.getTenant().getId())
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
