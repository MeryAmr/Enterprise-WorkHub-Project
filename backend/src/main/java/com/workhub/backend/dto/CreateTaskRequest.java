package com.workhub.backend.dto;

import lombok.Getter;

@Getter
public class CreateTaskRequest {
    // Intentionally no @NotBlank — validation happens inside the @Transactional
    // service method so that a blank title triggers a rollback of the auto-created project.
    private String title;
}
