package com.workhub.backend.service;

import com.workhub.backend.dto.CreateProjectRequest;
import com.workhub.backend.dto.ProjectResponse;
import com.workhub.backend.entity.Project;
import com.workhub.backend.entity.User;
import com.workhub.backend.exception.ResourceNotFoundException;
import com.workhub.backend.repository.ProjectRepository;
import com.workhub.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public ProjectService(ProjectRepository projectRepository, UserRepository userRepository) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ProjectResponse createProject(UUID userId, CreateProjectRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Project project = Project.builder()
                .tenant(user.getTenant())
                .name(request.getName())
                .createdBy(user)
                .build();

        project = projectRepository.save(project);
        return toResponse(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> getProjects(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return projectRepository.findAllByTenant_Id(user.getTenant().getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(UUID userId, UUID projectId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Project project = projectRepository.findByIdAndTenant_Id(projectId, user.getTenant().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        return toResponse(project);
    }

    private ProjectResponse toResponse(Project project) {
        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .tenantId(project.getTenant().getId())
                .createdBy(project.getCreatedBy().getId())
                .createdAt(project.getCreatedAt())
                .build();
    }
}
