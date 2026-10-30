package com.natsx.smartcafe.ops;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Uses the existing /api/v1/ops/** cashier/manager/admin authorization. */
@RestController
@RequestMapping("/api/v1/ops/reports")
public class CashReportController {
    private final DailyCashReportService reports;
    public CashReportController(DailyCashReportService reports) { this.reports = reports; }

    @GetMapping("/cash-daily")
    public ResponseEntity<ApiEnvelope<DailyCashReportService.DailyCashReport>> daily(
        @RequestParam(required = false) String date, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(reports.daily(date), RequestIdFilter.get(request)));
    }
}
