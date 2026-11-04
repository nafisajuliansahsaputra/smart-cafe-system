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

/** Both reports inherit the /api/v1/ops/** cashier/manager/admin role gate. */
@RestController
@RequestMapping("/api/v1/ops/reports")
public class CashReportController {
    private final DailyCashReportService reports;
    private final CashReceiptLedgerService ledger;

    public CashReportController(DailyCashReportService reports, CashReceiptLedgerService ledger) {
        this.reports = reports;
        this.ledger = ledger;
    }

    @GetMapping("/cash-daily")
    public ResponseEntity<ApiEnvelope<DailyCashReportService.DailyCashReport>> daily(
        @RequestParam(required = false) String date, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(reports.daily(date), RequestIdFilter.get(request)));
    }

    @GetMapping("/cash-daily/receipts")
    public ResponseEntity<ApiEnvelope<CashReceiptLedgerService.CashReceiptPage>> receipts(
        @RequestParam(required = false) String date,
        @RequestParam(defaultValue = "0") String page,
        HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(ledger.byDay(date, page), RequestIdFilter.get(request)));
    }
}
