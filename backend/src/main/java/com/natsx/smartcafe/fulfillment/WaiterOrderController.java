package com.natsx.smartcafe.fulfillment;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import com.natsx.smartcafe.orders.CashWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/waiter/orders")
public class WaiterOrderController {
    private final CashWorkflowService workflow;
    private final WaiterTaskQueryService tasks;
    public WaiterOrderController(CashWorkflowService workflow, WaiterTaskQueryService tasks) {
        this.workflow=workflow;
        this.tasks=tasks;
    }

    @GetMapping("/tasks")
    public ApiEnvelope<List<WaiterTaskQueryService.WaiterTask>> tasks(
        @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        return ApiEnvelope.of(tasks.tasks(staff),RequestIdFilter.get(request));
    }

    @PostMapping("/{id}/served")
    public ApiEnvelope<String> served(@PathVariable UUID id,@AuthenticationPrincipal StaffPrincipal staff,
                                     HttpServletRequest request) {
        workflow.served(id,staff.staffId(),RequestIdFilter.get(request));
        return ApiEnvelope.of("SERVED",RequestIdFilter.get(request));
    }
}
