package com.xupan.server.robot.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Request payloads for the robot administration API. */
public final class RobotAdminRequest {

    private RobotAdminRequest() {
    }

    public record CreateRobot(
            @NotBlank
            @Size(max = 32)
            @Pattern(regexp = "[a-z0-9_-]+")
            String robotCode,
            @NotBlank @Size(max = 32) String displayName,
            @NotBlank @Size(max = 64) String avatarKey,
            @NotNull @Min(1) @Max(100) Integer weight,
            @NotNull @Min(0) @Max(300) Integer delaySeconds
    ) {
    }

    public record UpdateRobot(
            @NotBlank @Size(max = 32) String displayName,
            @NotBlank @Size(max = 64) String avatarKey,
            @NotNull @Min(1) @Max(100) Integer weight,
            @NotNull @Min(0) @Max(300) Integer delaySeconds
    ) {
    }

    public record ChangeStatus(
            @NotBlank @Size(max = 16) String status
    ) {
    }

    public record UpdateTemplate(
            @NotBlank @Size(max = 1000) String templateText
    ) {
    }

    public record UpdateDrawComponents(
            @NotNull @Size(min = 3, max = 3) List<@jakarta.validation.Valid DrawComponent> components
    ) {
    }

    public record DrawComponent(
            @NotBlank @Size(max = 32) String component,
            boolean enabled,
            @NotNull @Min(1) @Max(3) Integer order
    ) {
    }
}
