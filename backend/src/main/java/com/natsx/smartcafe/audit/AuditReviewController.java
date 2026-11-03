package com.natsx.smartcafe.audit;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit-events")
public class AuditReviewController {
    private final AuditReviewService service;

    public AuditReviewController(AuditReviewService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<ApiEnvelope<AuditReviewService.AuditPage>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "") String q,
        HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(service.list(page, q), RequestIdFilter.get(request)));
    }
}
