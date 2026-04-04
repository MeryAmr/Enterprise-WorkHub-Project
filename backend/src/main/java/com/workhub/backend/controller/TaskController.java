package com.workhub.backend.controller;

import com.workhub.backend.dto.CreateTaskRequest;
import com.workhub.backend.dto.TaskResponse;
import com.workhub.backend.dto.UpdateTaskStatusRequest;
import com.workhub.backend.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/projects/{projectId}/tasks")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<TaskResponse> createTask(
            @PathVariable UUID projectId,
            @RequestBody CreateTaskRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskService.createTask(userId, projectId, request));
    }

    @GetMapping("/projects/{projectId}/tasks")
    public ResponseEntity<List<TaskResponse>> getTasksForProject(
            @PathVariable UUID projectId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(taskService.getTasksForProject(userId, projectId));
    }

    @PatchMapping("/tasks/{id}")
    public ResponseEntity<TaskResponse> updateTaskStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTaskStatusRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(taskService.updateTaskStatus(userId, id, request));
    }
}
