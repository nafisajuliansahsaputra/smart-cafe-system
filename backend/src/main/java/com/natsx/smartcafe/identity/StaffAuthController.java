package com.natsx.smartcafe.identity;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff/auth")
public class StaffAuthController {
    private final StaffAuthService service;
    private final StaffPasswordChangeService passwordChanges;

    public StaffAuthController(StaffAuthService service,
                               StaffPasswordChangeService passwordChanges) {
        this.service=service;
        this.passwordChanges=passwordChanges;
    }

    public record LoginRequest(@NotBlank @Size(max=120) String login,
                               @NotBlank @Size(min=8,max=128) String password) {}
    public record LoginData(String accessToken, String tokenType, Instant expiresAt) {}
    public record StaffData(UUID id, String login, Set<String> roles) {}

    @PostMapping("/login")
    public ResponseEntity<ApiEnvelope<LoginData>> login(@Valid @RequestBody LoginRequest payload,
                                                        HttpServletRequest request) {
        var result = service.login(payload.login(), payload.password());
        if (result.failure() != null) {
            if (result.failure().equals("RATE_LIMITED")) {
                throw new ApiFailureException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many attempts. Try again later.");
            }
            throw new ApiFailureException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Invalid credentials");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(new LoginData(result.token(), "Bearer", result.expiresAt()),
                RequestIdFilter.get(request)));
    }

    @GetMapping("/me")
    public ApiEnvelope<StaffData> me(@AuthenticationPrincipal StaffPrincipal principal,
                                    HttpServletRequest request) {
        return ApiEnvelope.of(new StaffData(principal.staffId(), principal.login(), principal.roles()),
            RequestIdFilter.get(request));
    }

    public record PasswordChangeRequest(
        @NotBlank @Size(max=128) String currentPassword,
        @NotBlank @Size(min=16,max=72) String newPassword) {}

    @PostMapping("/change-password")
    public ResponseEntity<ApiEnvelope<String>> changePassword(
        @AuthenticationPrincipal StaffPrincipal principal,
        @Valid @RequestBody PasswordChangeRequest payload,
        HttpServletRequest request) {
        if(payload.newPassword().chars().anyMatch(c->c<33||c>126)) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "New password must contain 16-72 printable ASCII characters");
        }
        var result=passwordChanges.change(principal,payload.currentPassword(),
            payload.newPassword(),RequestIdFilter.get(request));
        if(result==StaffPasswordChangeService.Result.INVALID_CREDENTIALS) {
            throw new ApiFailureException(HttpStatus.UNAUTHORIZED,"UNAUTHENTICATED",
                "Current password is incorrect");
        }
        if(result==StaffPasswordChangeService.Result.RATE_LIMITED) {
            throw new ApiFailureException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED",
                "Too many attempts. Try again later.");
        }
        if(result==StaffPasswordChangeService.Result.SAME_PASSWORD) {
            throw new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",
                "New password must differ from current password");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of("PASSWORD_CHANGED",RequestIdFilter.get(request)));
    }

    @PostMapping("/logout")
    public ApiEnvelope<String> logout(@AuthenticationPrincipal StaffPrincipal principal,
                                      HttpServletRequest request) {
        service.logout(principal);
        return ApiEnvelope.of("SESSION_REVOKED", RequestIdFilter.get(request));
    }
}
