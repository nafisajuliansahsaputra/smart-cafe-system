package com.natsx.smartcafe.ops;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ops/cash-shifts")
public class CashDrawerShiftController {
    private final CashDrawerShiftService shifts;
    public CashDrawerShiftController(CashDrawerShiftService shifts) { this.shifts = shifts; }

    public record OpenShift(@Min(0) @Max(1000000000000L) long openingFloatIdr) {}
    public record CloseShift(@Min(0) @Max(1000000000000L) long countedCashIdr,
                             @NotBlank @Size(min=5,max=300) String reason) {}

    @GetMapping
    public ResponseEntity<ApiEnvelope<CashDrawerShiftService.Dashboard>> dashboard(
        HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(shifts.dashboard(),RequestIdFilter.get(request)));
    }

    @PostMapping
    public ResponseEntity<ApiEnvelope<CashDrawerShiftService.Shift>> open(
        @AuthenticationPrincipal StaffPrincipal actor,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody OpenShift payload, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(shifts.open(actor,payload.openingFloatIdr(),
                key,RequestIdFilter.get(request)),RequestIdFilter.get(request)));
    }

    public record ReceiveCash(@Min(0) @Max(1000000000000L) long countedNetIdr,
                              boolean confirmedPhysicalReceipt) {}

    @PostMapping("/{id}/handoffs/{tenderId}/receive")
    public ResponseEntity<ApiEnvelope<CashDrawerShiftService.ReceivedHandoff>> receive(
        @PathVariable UUID id, @PathVariable UUID tenderId,
        @AuthenticationPrincipal StaffPrincipal actor,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody ReceiveCash payload, HttpServletRequest request) {
        if (!payload.confirmedPhysicalReceipt()) {
            throw new com.natsx.smartcafe.common.api.ApiFailureException(
                org.springframework.http.HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Physical cash receipt must be explicitly confirmed");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(shifts.receive(id,tenderId,actor,payload.countedNetIdr(),
                key,RequestIdFilter.get(request)),RequestIdFilter.get(request)));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<ApiEnvelope<CashDrawerShiftService.Shift>> close(
        @PathVariable UUID id, @AuthenticationPrincipal StaffPrincipal actor,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody CloseShift payload, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(shifts.close(id,actor,payload.countedCashIdr(),key,
                payload.reason(),RequestIdFilter.get(request)),RequestIdFilter.get(request)));
    }
}
