package com.natsx.smartcafe.receipts;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.GuestPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/receipts")
public class GuestReceiptController {
    private final GuestReceiptService receipts;
    public GuestReceiptController(GuestReceiptService receipts) { this.receipts=receipts; }

    @GetMapping("/{id}")
    public ResponseEntity<ApiEnvelope<GuestReceiptService.Receipt>> view(@PathVariable UUID id,
        @AuthenticationPrincipal GuestPrincipal guest,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(receipts.ownReceipt(id,guest),RequestIdFilter.get(request)));
    }
}
