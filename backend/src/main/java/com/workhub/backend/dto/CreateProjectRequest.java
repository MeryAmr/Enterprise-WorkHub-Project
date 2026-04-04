package com.workhub.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class CreateProjectRequest {

    @NotBlank(message = "Project name must not be blank")
    private String name;
}
