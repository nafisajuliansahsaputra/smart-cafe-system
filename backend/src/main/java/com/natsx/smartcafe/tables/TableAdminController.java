package com.natsx.smartcafe.tables;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Manager/Admin only by existing /api/v1/admin/** security policy. */
@RestController
@RequestMapping("/api/v1/admin/tables")
public class TableAdminController {
    private final TableAdminService tables;
    private final TableRegistryQueryService registry;
    public TableAdminController(TableAdminService tables, TableRegistryQueryService registry) {
        this.tables = tables;
        this.registry = registry;
    }

    public record TableCreate(@NotBlank String tableCode,
                              @NotBlank String publicAlias,
                              @Min(1) @Max(30) int capacity) {}
    public record TableEdit(@NotBlank String status,
                            @Min(1) @Max(30) int capacity,
                            @NotNull String expectedStatus,
                            @Min(1) @Max(30) int expectedCapacity) {}

    @GetMapping("/registry")
    public ResponseEntity<ApiEnvelope<TableRegistryQueryService.RegistryPage>> listRegistry(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "") String q,
        HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(registry.list(page, q), RequestIdFilter.get(request)));
    }

    @PostMapping
    public ResponseEntity<ApiEnvelope<TableAdminService.TableInfo>> create(
        @Valid @RequestBody TableCreate payload,
        @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(tables.create(payload.tableCode(), payload.publicAlias(),
                payload.capacity(), staff, RequestIdFilter.get(request)),
                RequestIdFilter.get(request)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiEnvelope<TableAdminService.TableInfo>> edit(
        @PathVariable UUID id, @Valid @RequestBody TableEdit payload,
        @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(tables.update(id, payload.status(), payload.capacity(),
                payload.expectedStatus(), payload.expectedCapacity(),
                staff, RequestIdFilter.get(request)), RequestIdFilter.get(request)));
    }
}
