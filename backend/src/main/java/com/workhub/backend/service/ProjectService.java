package com.workhub.backend.service;

import com.workhub.backend.dto.CreateProjectRequest;
import com.workhub.backend.dto.ProjectResponse;
import com.workhub.backend.dto.UpdateProjectRequest;
import com.workhub.backend.entity.Project;
import com.workhub.backend.exception.ResourceNotFoundException;
import com.workhub.backend.repository.ProjectRepository;
import com.workhub.backend.repository.TenantRepository;
import com.workhub.backend.repository.UserRepository;
import com.workhub.backend.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;

    public ProjectService(ProjectRepository projectRepository,
                          TenantRepository tenantRepository,
                          UserRepository userRepository) {
        this.projectRepository = projectRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ProjectResponse createProject(UUID userId, CreateProjectRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        Project project = Project.builder()
                .tenant(tenantRepository.getReferenceById(tenantId))
                .name(request.getName())
                .createdBy(userRepository.getReferenceById(userId))
                .build();

        return toResponse(projectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> getProjects() {
        return projectRepository.findAllByTenant_Id(TenantContext.getTenantId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(UUID projectId) {
        return projectRepository.findByIdAndTenant_Id(projectId, TenantContext.getTenantId())
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
    }

    @Transactional
    public ProjectResponse updateProject(UUID projectId, UpdateProjectRequest request) {
        Project project = projectRepository.findByIdAndTenant_Id(projectId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        project.setName(request.getName());
        return toResponse(project);
    }

    @Transactional
    public void deleteProject(UUID projectId) {
        Project project = projectRepository.findByIdAndTenant_Id(projectId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        projectRepository.delete(project);
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
