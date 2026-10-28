package com.natsx.smartcafe.orders;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.GuestPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CashCheckoutController {
    private final CashCheckoutService checkout;
    public CashCheckoutController(CashCheckoutService checkout) { this.checkout=checkout; }
    public record OrderRequest(@NotEmpty List<CashCheckoutService.Line> lines) {}

    @PostMapping("/orders")
    public ResponseEntity<ApiEnvelope<CashCheckoutService.Checkout>> submit(
        @AuthenticationPrincipal GuestPrincipal guest,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody OrderRequest body,HttpServletRequest request) {
        var result=checkout.checkout(guest,body.lines(),key);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(result,RequestIdFilter.get(request)));
    }

    @GetMapping("/me/orders")
    public ApiEnvelope<List<CashCheckoutService.Checkout>> ownOrders(
        @AuthenticationPrincipal GuestPrincipal guest,HttpServletRequest request) {
        return ApiEnvelope.of(checkout.ownOrders(guest),RequestIdFilter.get(request));
    }
}
