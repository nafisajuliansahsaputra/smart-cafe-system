package com.natsx.smartcafe.orders;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.GuestPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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
    private final GuestOrderDetailService details;
    public CashCheckoutController(CashCheckoutService checkout,GuestOrderDetailService details) {
        this.checkout=checkout;
        this.details=details;
    }
    public record OrderRequest(List<CashCheckoutService.Line> lines,Long cartVersion) {}

    @PostMapping("/orders")
    public ResponseEntity<ApiEnvelope<CashCheckoutService.Checkout>> submit(
        @AuthenticationPrincipal GuestPrincipal guest,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody OrderRequest body,HttpServletRequest request) {
        if((body.lines()==null)==(body.cartVersion()==null))
            throw new com.natsx.smartcafe.common.api.ApiFailureException(
                org.springframework.http.HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Provide either lines or cartVersion, not both");
        var result=body.cartVersion()==null
            ?checkout.checkout(guest,body.lines(),key)
            :checkout.checkoutFromCart(guest,body.cartVersion(),key);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(result,RequestIdFilter.get(request)));
    }

    @GetMapping("/me/orders/{id}")
    public ResponseEntity<ApiEnvelope<GuestOrderDetailService.Detail>> ownDetail(
        @org.springframework.web.bind.annotation.PathVariable UUID id,
        @AuthenticationPrincipal GuestPrincipal guest,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(details.ownOrder(guest,id),RequestIdFilter.get(request)));
    }

    @GetMapping("/me/orders")
    public ApiEnvelope<List<CashCheckoutService.Checkout>> ownOrders(
        @AuthenticationPrincipal GuestPrincipal guest,HttpServletRequest request) {
        return ApiEnvelope.of(checkout.ownOrders(guest),RequestIdFilter.get(request));
    }
}
