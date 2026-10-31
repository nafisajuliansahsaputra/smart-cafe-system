package com.natsx.smartcafe.payments;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Synthetic signed callback. Absent outside sandbox; cannot move money.
 * A signature proves possession of a LOCAL TEST SECRET, not settlement.
 */
@RestController
@ConditionalOnProperty(prefix="scs.payments",name="environment",havingValue="sandbox")
@RequestMapping("/api/v1/integrations/payments/sandbox/events")
public class SandboxSignedWebhookController {
    private final SandboxWebhookAuthenticator verifier;
    private final SandboxPaymentEventInbox inbox;
    private final JsonMapper mapper;
    private final boolean enabled;

    public SandboxSignedWebhookController(JdbcTemplate jdbc,
        PlatformTransactionManager manager,JsonMapper mapper,
        @Value("${scs.payments.sandbox-digital-checkout-enabled:false}") boolean enabled,
        @Value("${scs.payments.sandbox-webhook-secret:}") String secret) {
        this.enabled=enabled;
        this.mapper=mapper;
        this.verifier=new SandboxWebhookAuthenticator(Clock.systemUTC(),secret);
        this.inbox=new SandboxPaymentEventInbox(jdbc,new TransactionTemplate(manager));
    }

    public record Acknowledged(UUID evidenceId,String eventId,
                               String classification,String verdict) {}

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiEnvelope<Acknowledged>> receive(
        @RequestHeader(value="X-Sandbox-Timestamp",required=false) String timestamp,
        @RequestHeader(value="X-Sandbox-Signature",required=false) String signature,
        @RequestBody byte[] raw,HttpServletRequest request) {
        if(!enabled)throw new ApiFailureException(HttpStatus.FORBIDDEN,
            "FORBIDDEN","Sandbox event intake is disabled");
        verifier.verify(timestamp,signature,raw);

        SandboxPaymentEventInbox.Observation observation;
        try {
            observation=mapper.readerFor(SandboxPaymentEventInbox.Observation.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(raw);
        }catch(RuntimeException ex) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR","Invalid signed sandbox event body");
        }
        if(observation==null || observation.state()==null ||
           !Set.of(PaymentGateway.State.PENDING,PaymentGateway.State.SUCCEEDED,
               PaymentGateway.State.FAILED,PaymentGateway.State.EXPIRED)
               .contains(observation.state())) {
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR","Unsupported sandbox event state");
        }
        var evidence=inbox.record(observation);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(new Acknowledged(evidence.id(),evidence.eventId(),
                evidence.classification(),"QUARANTINED_NOT_PAYMENT"),
                RequestIdFilter.get(request)));
    }
}
