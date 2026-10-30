package com.natsx.smartcafe.identity;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/staff")
public class StaffManagementController {
    private final StaffManagementService management;
    public StaffManagementController(StaffManagementService management) {
        this.management=management;
    }
    public record ChangeStatus(boolean enabled,boolean expectedEnabled,
                               @NotBlank @Size(min=5,max=300) String reason) {}

    @GetMapping
    public ResponseEntity<ApiEnvelope<StaffManagementService.StaffPage>> list(
        @RequestParam(defaultValue="0") int page,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(management.list(page),RequestIdFilter.get(request)));
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<ApiEnvelope<String>> status(
        @PathVariable UUID id,@Valid @RequestBody ChangeStatus body,
        @AuthenticationPrincipal StaffPrincipal staff,HttpServletRequest request) {
        management.change(id,staff,body.enabled(),body.expectedEnabled(),
            body.reason(),RequestIdFilter.get(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(body.enabled()?"ENABLED":"DISABLED",RequestIdFilter.get(request)));
    }
}
