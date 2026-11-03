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
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/tables")
public class TableAdminController {
    private final TableAdminService tables;
    public TableAdminController(TableAdminService tables) { this.tables = tables; }

    public record Create(@NotBlank String tableCode, @NotBlank String publicAlias,
                         @NotNull @Min(1) @Max(100) Integer capacity) {}
    public record Update(@NotNull @Min(1) @Max(100) Integer capacity,
                         @NotBlank String status) {}

    @GetMapping
    public ResponseEntity<ApiEnvelope<List<TableAdminService.TableRow>>> list(
        HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(tables.list(), RequestIdFilter.get(req)));
    }

    @PostMapping
    public ResponseEntity<ApiEnvelope<TableAdminService.TableRow>> create(
        @Valid @RequestBody Create payload,
        @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(tables.create(payload.tableCode(), payload.publicAlias(),
                payload.capacity(), staff, RequestIdFilter.get(req)), RequestIdFilter.get(req)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiEnvelope<TableAdminService.TableRow>> update(
        @PathVariable UUID id, @Valid @RequestBody Update payload,
        @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(tables.update(id, payload.capacity(), payload.status(),
                staff, RequestIdFilter.get(req)), RequestIdFilter.get(req)));
    }
}
