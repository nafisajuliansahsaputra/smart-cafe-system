package com.natsx.smartcafe.games;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class GameLossIncidentController {
    private final GameLossIncidentService service;
    public GameLossIncidentController(GameLossIncidentService service) {
        this.service=service;
    }
    public record NewReport(@NotBlank @Size(min=5,max=300) String reason) {}
    public record Decision(@NotBlank String decision, String copyCode,
                           @NotBlank @Size(min=5,max=300) String reason) {}

    @PostMapping("/waiter/game-loans/{id}/report-loss")
    public ApiEnvelope<GameLossIncidentService.IncidentView> report(
        @AuthenticationPrincipal StaffPrincipal staff,@PathVariable UUID id,
        @Valid @RequestBody NewReport body,HttpServletRequest request) {
        return ApiEnvelope.of(service.report(id,body.reason(),staff,RequestIdFilter.get(request)),
            RequestIdFilter.get(request));
    }
    @GetMapping("/admin/games/loss-incidents")
    public ResponseEntity<ApiEnvelope<List<GameLossIncidentService.IncidentView>>> list(
        @AuthenticationPrincipal StaffPrincipal staff,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(service.reviewQueue(),RequestIdFilter.get(request)));
    }
    /** Search the full incident register without the legacy first-100 cap. */
    @GetMapping("/admin/games/loss-incidents/search")
    public ResponseEntity<ApiEnvelope<GameLossIncidentService.IncidentPage>> search(
        @AuthenticationPrincipal StaffPrincipal staff,
        @RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="") String status,
        @RequestParam(defaultValue="0") int page,
        HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(service.searchIncidents(q,status,page),
                RequestIdFilter.get(request)));
    }
    @PostMapping("/admin/games/loss-incidents/{id}/review")
    public ApiEnvelope<GameLossIncidentService.IncidentView> review(
        @AuthenticationPrincipal StaffPrincipal staff,@PathVariable UUID id,
        @Valid @RequestBody Decision body,HttpServletRequest request) {
        return ApiEnvelope.of(service.review(id,body.decision(),body.copyCode(),
            body.reason(),staff,RequestIdFilter.get(request)),RequestIdFilter.get(request));
    }
}
