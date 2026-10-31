package com.natsx.smartcafe.payments;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final SandboxTriageAudit triage;
    private final SandboxUnresolvedAttemptReview attempts;
    private final boolean enabled;

    public SandboxReconciliationController(JdbcTemplate jdbc,
        PlatformTransactionManager manager,
        @Value("${scs.payments.sandbox-digital-checkout-enabled:false}") boolean enabled) {
        this.review=new SandboxReconciliationReview(jdbc);
        this.triage=new SandboxTriageAudit(jdbc,new TransactionTemplate(manager));
        this.attempts=new SandboxUnresolvedAttemptReview(jdbc,java.time.Clock.systemUTC());
        this.enabled=enabled;
    }

    public record ActionRequest(String code) {}

    private void checkAccess(StaffPrincipal staff) {
        if(staff==null||staff.roles()==null||!staff.roles().contains("ADMIN"))
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Administrator review required");
        if(!enabled)
            throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
                "Sandbox review is disabled");
    }

    @PostMapping("/{id}/triage")
    public ResponseEntity<ApiEnvelope<SandboxTriageAudit.Action>> appendTriage(
        @PathVariable UUID id,@AuthenticationPrincipal StaffPrincipal staff,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @RequestBody ActionRequest action,HttpServletRequest request) {
        checkAccess(staff);
        var recorded=triage.append(staff,id,action==null?null:action.code(),key);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(recorded,RequestIdFilter.get(request)));
    }

    @GetMapping("/attempts")
    public ResponseEntity<ApiEnvelope<SandboxUnresolvedAttemptReview.Page>> pendingAttempts(
        @AuthenticationPrincipal StaffPrincipal staff,
        @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="ALL") String filter,
        HttpServletRequest request) {
        checkAccess(staff);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(attempts.list(page,filter),RequestIdFilter.get(request)));
    }

    @GetMapping
    public ResponseEntity<ApiEnvelope<SandboxReconciliationReview.Page>> list(
        @AuthenticationPrincipal StaffPrincipal staff,
        @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="ALL") String classification,
        HttpServletRequest request) {
        checkAccess(staff);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(review.list(page,classification),
                RequestIdFilter.get(request)));
    }
}
