package com.natsx.smartcafe.payments;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.GuestPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sandbox-only guest API. This controller is NOT registered in normal/production
 * environments even if the feature flag is accidentally set. Its methods
 * additionally reject requests without the independent opt-in flag.
 * No operation here can settle money or create receipts/kitchen tickets.
 */
@RestController
@ConditionalOnProperty(prefix="scs.payments",name="environment",havingValue="sandbox")
@RequestMapping("/api/v1/me/orders")
public class SandboxPaymentAttemptController {
    private final SandboxDigitalAttemptService service;
    private final boolean enabled;

    public SandboxPaymentAttemptController(JdbcTemplate jdbc,
        PlatformTransactionManager manager,
        @Value("${scs.payments.sandbox-digital-checkout-enabled:false}") boolean enabled) {
        Clock clock=Clock.systemUTC();
        this.service=new SandboxDigitalAttemptService(jdbc,new TransactionTemplate(manager),
            new SandboxFakePaymentGateway(clock),clock);
        this.enabled=enabled;
    }

    public record CreateRequest(@NotNull PaymentGateway.Method method) {}

    private void checkSandbox() {
        if(!enabled)throw new ApiFailureException(HttpStatus.FORBIDDEN,"FORBIDDEN",
            "Sandbox payment attempts are disabled");
    }

    @PostMapping("/{id}/sandbox-attempts")
    public ResponseEntity<ApiEnvelope<SandboxDigitalAttemptService.Attempt>> create(
        @PathVariable UUID id,
        @AuthenticationPrincipal GuestPrincipal guest,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody CreateRequest body,HttpServletRequest request) {
        checkSandbox();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(service.create(guest,id,body.method(),key),
                RequestIdFilter.get(request)));
    }

    @GetMapping("/{id}/sandbox-attempts")
    public ResponseEntity<ApiEnvelope<List<SandboxDigitalAttemptService.Attempt>>> list(
        @PathVariable UUID id,@AuthenticationPrincipal GuestPrincipal guest,
        HttpServletRequest request) {
        checkSandbox();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(service.ownAttempts(guest,id),RequestIdFilter.get(request)));
    }
}
