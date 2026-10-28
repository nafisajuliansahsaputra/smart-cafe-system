package com.natsx.smartcafe.catalog;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
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
@RequestMapping("/api/v1/admin")
public class CatalogAdminController {
    private final CatalogAdminService catalog;
    public CatalogAdminController(CatalogAdminService catalog) { this.catalog=catalog; }

    public record CategoryCreate(@NotBlank @Size(max=120) String name,
                                 @NotBlank String slug) {}
    public record CategoryToggle(@NotNull Boolean active) {}
    public record ItemCreate(@NotNull UUID categoryId,@NotBlank @Size(max=120) String name,
                             @NotNull @PositiveOrZero Long priceIdr) {}
    public record ItemEdit(@NotNull UUID categoryId,@NotBlank @Size(max=120) String name,
                           @NotNull @PositiveOrZero Long priceIdr,
                           @NotBlank String availability,
                           @NotNull Long expectedRevision) {}

    @GetMapping("/menu-categories")
    public ResponseEntity<ApiEnvelope<List<CatalogAdminService.Category>>> categories(HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(catalog.categories(),RequestIdFilter.get(req)));
    }

    @PostMapping("/menu-categories")
    public ResponseEntity<ApiEnvelope<CatalogAdminService.Category>> createCategory(
        @Valid @RequestBody CategoryCreate payload,@AuthenticationPrincipal StaffPrincipal staff,
        HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(catalog.createCategory(payload.name(),payload.slug(),
                staff,RequestIdFilter.get(req)),RequestIdFilter.get(req)));
    }

    @PatchMapping("/menu-categories/{id}/active")
    public ResponseEntity<ApiEnvelope<CatalogAdminService.Category>> categoryActive(
        @PathVariable UUID id,@Valid @RequestBody CategoryToggle payload,
        @AuthenticationPrincipal StaffPrincipal staff,HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(catalog.setCategoryActive(id,payload.active(),
                staff,RequestIdFilter.get(req)),RequestIdFilter.get(req)));
    }

    @GetMapping("/menu-items")
    public ResponseEntity<ApiEnvelope<List<CatalogAdminService.Item>>> items(HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(catalog.items(),RequestIdFilter.get(req)));
    }

    @GetMapping("/menu-items/{id}")
    public ResponseEntity<ApiEnvelope<CatalogAdminService.Item>> item(@PathVariable UUID id,
                                                                        HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(catalog.detail(id),RequestIdFilter.get(req)));
    }

    @PostMapping("/menu-items")
    public ResponseEntity<ApiEnvelope<CatalogAdminService.Item>> createItem(
        @Valid @RequestBody ItemCreate payload,@AuthenticationPrincipal StaffPrincipal staff,
        HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(catalog.createItem(payload.categoryId(),payload.name(),
                payload.priceIdr(),staff,RequestIdFilter.get(req)),RequestIdFilter.get(req)));
    }

    @PatchMapping("/menu-items/{id}")
    public ResponseEntity<ApiEnvelope<CatalogAdminService.Item>> editItem(
        @PathVariable UUID id,@Valid @RequestBody ItemEdit payload,
        @AuthenticationPrincipal StaffPrincipal staff,HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(catalog.changeItem(id,payload.categoryId(),payload.name(),
                payload.priceIdr(),payload.availability(),payload.expectedRevision(),
                staff,RequestIdFilter.get(req)),RequestIdFilter.get(req)));
    }
}
