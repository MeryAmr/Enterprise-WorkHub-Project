package com.workhub.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class InviteUserRequest {

    @Email(message = "Must be a valid email address")
    @NotBlank(message = "Email must not be blank")
    private String email;
}
