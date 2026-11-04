package com.natsx.smartcafe.identity;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class GuestLogoutController {
    private final JdbcTemplate jdbc;
    public GuestLogoutController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostMapping("/logout")
    public ApiEnvelope<String> logout(@AuthenticationPrincipal GuestPrincipal guest,
                                      HttpServletRequest request) {
        jdbc.update("""
            UPDATE guest_sessions SET revoked_at=now()
            WHERE token_hash=? AND guest_id=? AND visit_id=? AND revoked_at IS NULL
            """, guest.tokenHash(), guest.guestId(), guest.visitId());
        return ApiEnvelope.of("SESSION_REVOKED", RequestIdFilter.get(request));
    }
}
