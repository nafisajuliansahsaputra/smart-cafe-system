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
    private final StaffPasswordService passwordService;

    public StaffAuthController(StaffAuthService service, StaffPasswordService passwordService) {
        this.service = service;
        this.passwordService = passwordService;
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

    public record ChangePasswordRequest(
        @NotBlank @Size(max=128) String currentPassword,
        @NotBlank @Size(min=16,max=72) String newPassword) {}

    /**
     * A successful rotation invalidates every session, including this bearer.
     * The caller must discard its cookie and authenticate again.
     */
    @PostMapping("/password/change")
    public ResponseEntity<ApiEnvelope<String>> changePassword(
        @Valid @RequestBody ChangePasswordRequest payload,
        @AuthenticationPrincipal StaffPrincipal principal,
        HttpServletRequest request) {
        var result = passwordService.change(principal, payload.currentPassword(),
            payload.newPassword(), RequestIdFilter.get(request));
        switch (result) {
            case INVALID_CURRENT -> throw new ApiFailureException(HttpStatus.FORBIDDEN,
                "INVALID_CURRENT_PASSWORD", "Current password is not valid");
            case RATE_LIMITED -> throw new ApiFailureException(HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED", "Too many attempts. Try again later.");
            case REUSED -> throw new ApiFailureException(HttpStatus.CONFLICT,
                "CONFLICT", "Choose a different password");
            case REVOKED -> throw new ApiFailureException(HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED", "Session is no longer valid");
            case CHANGED -> {}
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of("PASSWORD_CHANGED_SESSIONS_REVOKED",
                RequestIdFilter.get(request)));
    }

    @GetMapping("/me")
    public ApiEnvelope<StaffData> me(@AuthenticationPrincipal StaffPrincipal principal,
                                    HttpServletRequest request) {
        return ApiEnvelope.of(new StaffData(principal.staffId(), principal.login(), principal.roles()),
            RequestIdFilter.get(request));
    }

    @PostMapping("/logout")
    public ApiEnvelope<String> logout(@AuthenticationPrincipal StaffPrincipal principal,
                                      HttpServletRequest request) {
        service.logout(principal);
        return ApiEnvelope.of("SESSION_REVOKED", RequestIdFilter.get(request));
    }
}
