package com.natsx.smartcafe.ops;

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
@RequestMapping("/api/v1/ops/orders")
public class OrderControlController {
    private final CashWorkflowService workflow;
    public OrderControlController(CashWorkflowService workflow) { this.workflow=workflow; }

    @GetMapping
    public ApiEnvelope<List<CashWorkflowService.OpsOrder>> queue(HttpServletRequest request) {
        return ApiEnvelope.of(workflow.queue(),RequestIdFilter.get(request));
    }

    @PostMapping("/{id}/approve-cash")
    public ApiEnvelope<String> approve(@PathVariable UUID id,
        @AuthenticationPrincipal StaffPrincipal staff,HttpServletRequest request) {
        workflow.approve(id,staff.staffId(),RequestIdFilter.get(request));
        return ApiEnvelope.of("ACCEPTED",RequestIdFilter.get(request));
    }
}
