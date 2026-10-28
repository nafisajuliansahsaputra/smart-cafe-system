package com.natsx.smartcafe.catalog;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class VariantCatalogController {
    private final VariantCatalogService variants;
    public VariantCatalogController(VariantCatalogService variants){this.variants=variants;}

    public record GroupCreate(@NotBlank String name,@NotNull Boolean required) {}
    public record GroupEdit(@NotBlank String name,@NotNull Boolean required,
                            @NotNull Boolean active,@NotNull Long expectedRevision) {}
    public record OptionCreate(@NotBlank String key,@NotBlank String label,
                               @NotNull Long priceDeltaIdr) {}
    public record OptionEdit(@NotBlank String label,@NotNull Long priceDeltaIdr,
                             @NotNull Boolean active,@NotNull Long expectedRevision) {}

    @GetMapping("/menu/{itemId}/variants")
    public ResponseEntity<ApiEnvelope<VariantCatalogService.Group>> published(@PathVariable UUID itemId,
                                                                               HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(variants.publicGroup(itemId),RequestIdFilter.get(request)));
    }

    @GetMapping("/admin/menu-items/{itemId}/variant-group")
    public ApiEnvelope<VariantCatalogService.Group> admin(@PathVariable UUID itemId,
                                                           HttpServletRequest request) {
        return ApiEnvelope.of(variants.adminGroup(itemId),RequestIdFilter.get(request));
    }

    @PostMapping("/admin/menu-items/{itemId}/variant-group")
    public ApiEnvelope<VariantCatalogService.Group> createGroup(@PathVariable UUID itemId,
        @Valid @RequestBody GroupCreate payload,@AuthenticationPrincipal StaffPrincipal staff,
        HttpServletRequest request) {
        return ApiEnvelope.of(variants.createGroup(itemId,payload.name(),payload.required(),
            staff,RequestIdFilter.get(request)),RequestIdFilter.get(request));
    }

    @PatchMapping("/admin/variant-groups/{id}")
    public ApiEnvelope<VariantCatalogService.Group> editGroup(@PathVariable UUID id,
        @Valid @RequestBody GroupEdit payload,@AuthenticationPrincipal StaffPrincipal staff,
        HttpServletRequest request) {
        return ApiEnvelope.of(variants.editGroup(id,payload.name(),payload.required(),
            payload.active(),payload.expectedRevision(),staff,RequestIdFilter.get(request)),
            RequestIdFilter.get(request));
    }

    @PostMapping("/admin/variant-groups/{id}/options")
    public ApiEnvelope<VariantCatalogService.Option> createOption(@PathVariable UUID id,
        @Valid @RequestBody OptionCreate payload,@AuthenticationPrincipal StaffPrincipal staff,
        HttpServletRequest request) {
        return ApiEnvelope.of(variants.createOption(id,payload.key(),payload.label(),
            payload.priceDeltaIdr(),staff,RequestIdFilter.get(request)),RequestIdFilter.get(request));
    }

    @PatchMapping("/admin/variant-options/{id}")
    public ApiEnvelope<VariantCatalogService.Option> editOption(@PathVariable UUID id,
        @Valid @RequestBody OptionEdit payload,@AuthenticationPrincipal StaffPrincipal staff,
        HttpServletRequest request) {
        return ApiEnvelope.of(variants.editOption(id,payload.label(),payload.priceDeltaIdr(),
            payload.active(),payload.expectedRevision(),staff,RequestIdFilter.get(request)),
            RequestIdFilter.get(request));
    }
}
