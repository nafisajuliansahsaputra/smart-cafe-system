package com.natsx.smartcafe.fulfillment;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import com.natsx.smartcafe.orders.CashWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/kitchen/tickets")
public class KitchenController {
    private final CashWorkflowService workflow;
    public KitchenController(CashWorkflowService workflow) { this.workflow=workflow; }

    @GetMapping
    public ApiEnvelope<List<CashWorkflowService.KitchenTicket>> tickets(HttpServletRequest request) {
        return ApiEnvelope.of(workflow.kitchen(),RequestIdFilter.get(request));
    }

    @PostMapping("/{id}/start")
    public ApiEnvelope<String> start(@PathVariable UUID id,@AuthenticationPrincipal StaffPrincipal staff,
                                     HttpServletRequest request) {
        workflow.kitchenStart(id,staff.staffId(),RequestIdFilter.get(request));
        return ApiEnvelope.of("PREPARING",RequestIdFilter.get(request));
    }

    @PostMapping("/{id}/ready")
    public ApiEnvelope<String> ready(@PathVariable UUID id,@AuthenticationPrincipal StaffPrincipal staff,
                                     HttpServletRequest request) {
        workflow.kitchenReady(id,staff.staffId(),RequestIdFilter.get(request));
        return ApiEnvelope.of("READY",RequestIdFilter.get(request));
    }
}
