package com.natsx.smartcafe.fulfillment;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import com.natsx.smartcafe.orders.CashWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/waiter/orders")
public class WaiterOrderController {
    private final CashWorkflowService workflow;
    public WaiterOrderController(CashWorkflowService workflow) { this.workflow=workflow; }

    @PostMapping("/{id}/served")
    public ApiEnvelope<String> served(@PathVariable UUID id,@AuthenticationPrincipal StaffPrincipal staff,
                                     HttpServletRequest request) {
        workflow.served(id,staff.staffId(),RequestIdFilter.get(request));
        return ApiEnvelope.of("SERVED",RequestIdFilter.get(request));
    }
}
