package com.natsx.smartcafe.payments;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only read-only quarantine view, completely absent outside sandbox.
 * Never exposes a way to approve synthetic success or mutate paid status.
 */
@RestController
@ConditionalOnProperty(prefix="scs.payments",name="environment",havingValue="sandbox")
@RequestMapping("/api/v1/admin/payments/sandbox-events")
public class SandboxReconciliationController {
    private final SandboxReconciliationReview review;
    private final boolean enabled;

    public SandboxReconciliationController(JdbcTemplate jdbc,
        @Value("${scs.payments.sandbox-digital-checkout-enabled:false}") boolean enabled) {
        this.review=new SandboxReconciliationReview(jdbc);
        this.enabled=enabled;
    }

    @GetMapping
    public ResponseEntity<ApiEnvelope<SandboxReconciliationReview.Page>> list(
        @AuthenticationPrincipal StaffPrincipal staff,
        @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="ALL") String classification,
        HttpServletRequest request) {
        if(staff==null||!staff.roles().contains("ADMIN"))
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Administrator review required");
        if(!enabled)
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Sandbox review is disabled");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(review.list(page,classification),
                RequestIdFilter.get(request)));
    }
}
