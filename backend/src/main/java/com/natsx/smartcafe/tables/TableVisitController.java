package com.natsx.smartcafe.tables;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.GuestPrincipal;
import com.natsx.smartcafe.identity.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TableVisitController {
    private final TableVisitService visits;
    public TableVisitController(TableVisitService visits) { this.visits = visits; }

    public record OpenRequest(UUID tableId) {}
    public record JoinRequest(@NotBlank String tableAlias, @NotBlank String joinProof,
                              @NotBlank @Size(max=80) String displayName) {}

    @GetMapping("/tables/{alias}/entry")
    public ApiEnvelope<TableVisitService.TableEntry> entry(@PathVariable String alias,
                                                            HttpServletRequest request) {
        return ApiEnvelope.of(visits.entry(alias), RequestIdFilter.get(request));
    }

    @PostMapping("/waiter/table-visits")
    public ResponseEntity<ApiEnvelope<TableVisitService.VisitOpened>> open(
        @Valid @RequestBody OpenRequest payload, @AuthenticationPrincipal StaffPrincipal staff,
        HttpServletRequest request) {
        var data = visits.open(payload.tableId(), staff.staffId(), RequestIdFilter.get(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(data, RequestIdFilter.get(request)));
    }

    @PostMapping("/waiter/table-visits/{id}/join-proof")
    public ResponseEntity<ApiEnvelope<TableVisitService.VisitOpened>> rotate(@PathVariable UUID id,
        @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(visits.rotate(id, staff.staffId(), RequestIdFilter.get(request)), RequestIdFilter.get(request)));
    }

    @PostMapping("/waiter/table-visits/{id}/close")
    public ApiEnvelope<String> close(@PathVariable UUID id,
        @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        visits.close(id, staff.staffId(), RequestIdFilter.get(request));
        return ApiEnvelope.of("CLOSED", RequestIdFilter.get(request));
    }

    @PostMapping("/table-visits/join")
    public ResponseEntity<ApiEnvelope<TableVisitService.JoinResult>> join(
        @Valid @RequestBody JoinRequest payload, HttpServletRequest request) {
        var result = visits.join(payload.tableAlias(), payload.joinProof(), payload.displayName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(result, RequestIdFilter.get(request)));
    }

    @GetMapping("/me/visit")
    public ApiEnvelope<TableVisitService.GuestVisit> currentVisit(
        @AuthenticationPrincipal GuestPrincipal guest, HttpServletRequest request) {
        return ApiEnvelope.of(visits.currentVisit(guest.guestId(), guest.visitId()),
            RequestIdFilter.get(request));
    }
}
