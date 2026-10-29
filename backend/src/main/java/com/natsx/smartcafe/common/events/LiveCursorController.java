package com.natsx.smartcafe.common.events;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.GuestPrincipal;
import com.natsx.smartcafe.identity.GuestSessionService;
import com.natsx.smartcafe.identity.StaffAuthService;
import com.natsx.smartcafe.identity.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal Bearer-only cursor endpoints. Never expose an anonymous cursor. */
@RestController
public class LiveCursorController {
    private final LiveCursorService cursors;
    private final StaffAuthService staffAuth;
    private final GuestSessionService guestAuth;

    public LiveCursorController(LiveCursorService cursors, StaffAuthService staffAuth,
                                GuestSessionService guestAuth) {
        this.cursors = cursors;
        this.staffAuth = staffAuth;
        this.guestAuth = guestAuth;
    }

    private String bearer(HttpServletRequest request) {
        String value = request.getHeader("Authorization");
        return value != null && value.startsWith("Bearer ") ? value.substring(7) : "";
    }

    @GetMapping("/api/v1/me/live-cursor")
    public ApiEnvelope<LiveCursorService.Cursor> guest(
        @AuthenticationPrincipal GuestPrincipal guest,
        @RequestParam String topic,
        @RequestParam(required = false) UUID orderId,
        @RequestParam(required = false) Long after,
        HttpServletRequest request) throws InterruptedException {
        String token = bearer(request);
        return ApiEnvelope.of(cursors.guest(topic, orderId, after, guest,
            () -> guestAuth.authenticate(token) != null), RequestIdFilter.get(request));
    }

    @GetMapping("/api/v1/ops/live-cursor")
    public ApiEnvelope<LiveCursorService.Cursor> ops(
        @AuthenticationPrincipal StaffPrincipal staff,
        @RequestParam String topic,
        @RequestParam(required = false) UUID orderId,
        @RequestParam(required = false) Long after,
        HttpServletRequest request) throws InterruptedException {
        return ApiEnvelope.of(staffCursor("ops", topic, orderId, after, staff, request),
            RequestIdFilter.get(request));
    }

    @GetMapping("/api/v1/kitchen/live-cursor")
    public ApiEnvelope<LiveCursorService.Cursor> kitchen(
        @AuthenticationPrincipal StaffPrincipal staff,
        @RequestParam String topic,
        @RequestParam(required = false) UUID orderId,
        @RequestParam(required = false) Long after,
        HttpServletRequest request) throws InterruptedException {
        return ApiEnvelope.of(staffCursor("kitchen", topic, orderId, after, staff, request),
            RequestIdFilter.get(request));
    }

    @GetMapping("/api/v1/waiter/live-cursor")
    public ApiEnvelope<LiveCursorService.Cursor> waiter(
        @AuthenticationPrincipal StaffPrincipal staff,
        @RequestParam String topic,
        @RequestParam(required = false) UUID orderId,
        @RequestParam(required = false) Long after,
        HttpServletRequest request) throws InterruptedException {
        return ApiEnvelope.of(staffCursor("waiter", topic, orderId, after, staff, request),
            RequestIdFilter.get(request));
    }

    private LiveCursorService.Cursor staffCursor(String scope, String topic, UUID orderId,
        Long after, StaffPrincipal staff, HttpServletRequest request)
        throws InterruptedException {
        String token = bearer(request);
        return cursors.staff(scope, topic, orderId, after, staff, () -> {
            StaffPrincipal current = staffAuth.authenticate(token);
            if (current == null || !current.staffId().equals(staff.staffId())) return false;
            return current.roles().containsAll(staff.roles());
        });
    }
}
