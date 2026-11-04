package com.natsx.smartcafe.payments;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import com.natsx.smartcafe.orders.CashWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ops/payments")
public class CashTenderController {
    private final CashWorkflowService workflow;
    public CashTenderController(CashWorkflowService workflow) { this.workflow=workflow; }

    public record TenderRequest(@Positive long amountReceivedIdr) {}

    @PostMapping("/{intentId}/cash-tenders")
    public ResponseEntity<ApiEnvelope<CashWorkflowService.CashTenderResult>> capture(
        @PathVariable UUID intentId,@AuthenticationPrincipal StaffPrincipal staff,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody TenderRequest body,HttpServletRequest request) {
        var result=workflow.capture(intentId,body.amountReceivedIdr(),key,staff.staffId(),
            RequestIdFilter.get(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(result,RequestIdFilter.get(request)));
    }
}
