package com.workhub.backend.dto;

import com.workhub.backend.entity.TaskStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class UpdateTaskStatusRequest {

    @NotNull(message = "Status must not be null")
    private TaskStatus status;
}
