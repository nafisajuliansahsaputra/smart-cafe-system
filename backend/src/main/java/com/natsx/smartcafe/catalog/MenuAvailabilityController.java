package com.natsx.smartcafe.catalog;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/menu-items/{id}/availability")
public class MenuAvailabilityController {
    private final MenuAvailabilityService inventory;
    public MenuAvailabilityController(MenuAvailabilityService inventory) { this.inventory=inventory; }

    public record EditRequest(@NotNull Boolean available,Long limitedQuantity,
                              @NotNull Long expectedVersion,
                              @NotBlank @Size(min=4,max=300) String reason) {}

    @GetMapping
    public ResponseEntity<ApiEnvelope<MenuAvailabilityService.Availability>> get(
        @PathVariable UUID id,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(inventory.current(id),RequestIdFilter.get(request)));
    }

    @PatchMapping
    public ResponseEntity<ApiEnvelope<MenuAvailabilityService.Availability>> patch(
        @PathVariable UUID id,@AuthenticationPrincipal StaffPrincipal staff,
        @Valid @RequestBody EditRequest payload,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(inventory.set(id,payload.available(),payload.limitedQuantity(),
                payload.expectedVersion(),payload.reason(),staff,RequestIdFilter.get(request)),
                RequestIdFilter.get(request)));
    }
}
