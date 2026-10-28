package com.natsx.smartcafe.orders;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.GuestPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/cart")
public class CartController {
    private final CartService carts;
    public CartController(CartService carts) { this.carts=carts; }

    public record ReplaceRequest(@Min(0) long expectedVersion,
                                 @NotNull List<CashCheckoutService.Line> lines) {}

    @GetMapping
    public ResponseEntity<ApiEnvelope<CartService.CartView>> view(
        @AuthenticationPrincipal GuestPrincipal guest,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(carts.get(guest),RequestIdFilter.get(request)));
    }

    @PutMapping
    public ResponseEntity<ApiEnvelope<CartService.CartView>> replace(
        @AuthenticationPrincipal GuestPrincipal guest,
        @Valid @RequestBody ReplaceRequest payload,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(carts.replace(guest,payload.expectedVersion(),payload.lines()),
                RequestIdFilter.get(request)));
    }
}
