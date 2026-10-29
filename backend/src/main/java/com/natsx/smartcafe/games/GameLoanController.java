package com.natsx.smartcafe.games;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.GuestPrincipal;
import com.natsx.smartcafe.identity.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
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
public class GameLoanController {
    private final GameLoanService games;
    public GameLoanController(GameLoanService games) { this.games=games; }

    public record RequestGame(@NotNull UUID gameId) {}
    public record VerifyCopy(@NotBlank @Size(max=80) String copyCode) {}
    public record NewGame(@NotBlank @Size(max=120) String name,
                          @Size(max=500) String description) {}
    public record NewCopy(@NotBlank @Size(max=80) String copyCode) {}
    public record DamagedReturn(@NotBlank @Size(max=80) String copyCode,
                                @NotBlank @Size(min=5,max=300) String reason) {}
    public record ConditionChange(@NotBlank String condition,
                                  @NotBlank @Size(min=5,max=300) String reason) {}

    @GetMapping("/me/games")
    public ResponseEntity<ApiEnvelope<List<GameLoanService.GameView>>> catalog(
        @AuthenticationPrincipal GuestPrincipal guest,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(games.catalog(),RequestIdFilter.get(request)));
    }

    @GetMapping("/me/game-loans")
    public ResponseEntity<ApiEnvelope<List<GameLoanService.LoanView>>> mine(
        @AuthenticationPrincipal GuestPrincipal guest,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(games.mine(guest),RequestIdFilter.get(request)));
    }

    @PostMapping("/me/game-loans")
    public ResponseEntity<ApiEnvelope<GameLoanService.LoanView>> request(
        @AuthenticationPrincipal GuestPrincipal guest,@Valid @RequestBody RequestGame body,
        HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(games.request(guest,body.gameId()),RequestIdFilter.get(request)));
    }

    /** Customer requests pickup; this never confirms a physical return. */
    @PostMapping("/me/game-loans/{id}/request-return")
    public ResponseEntity<ApiEnvelope<GameLoanService.LoanView>> requestReturn(
        @AuthenticationPrincipal GuestPrincipal guest,@PathVariable UUID id,
        HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(games.requestReturn(guest,id),
                RequestIdFilter.get(request)));
    }

    @GetMapping("/waiter/games")
    public ResponseEntity<ApiEnvelope<List<GameLoanService.GameView>>> staffCatalog(
        @AuthenticationPrincipal StaffPrincipal staff,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(games.catalog(),RequestIdFilter.get(request)));
    }

    @GetMapping("/admin/games")
    public ResponseEntity<ApiEnvelope<List<GameLoanService.GameView>>> adminCatalog(
        @AuthenticationPrincipal StaffPrincipal staff,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(games.catalog(),RequestIdFilter.get(request)));
    }

    @GetMapping("/waiter/game-loans")
    public ResponseEntity<ApiEnvelope<List<GameLoanService.LoanView>>> tasks(
        @AuthenticationPrincipal StaffPrincipal staff,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(games.tasks(staff),RequestIdFilter.get(request)));
    }

    @PostMapping("/waiter/game-loans/{id}/assign")
    public ApiEnvelope<GameLoanService.LoanView> assign(
        @AuthenticationPrincipal StaffPrincipal staff,@PathVariable UUID id,
        HttpServletRequest request) {
        return ApiEnvelope.of(games.assign(id,staff,RequestIdFilter.get(request)),
            RequestIdFilter.get(request));
    }

    @PostMapping("/waiter/game-loans/{id}/handover")
    public ApiEnvelope<GameLoanService.LoanView> handover(
        @AuthenticationPrincipal StaffPrincipal staff,@PathVariable UUID id,
        @Valid @RequestBody VerifyCopy body,HttpServletRequest request) {
        return ApiEnvelope.of(games.handover(id,body.copyCode(),staff,RequestIdFilter.get(request)),
            RequestIdFilter.get(request));
    }

    @PostMapping("/waiter/game-loans/{id}/return")
    public ApiEnvelope<GameLoanService.LoanView> returned(
        @AuthenticationPrincipal StaffPrincipal staff,@PathVariable UUID id,
        @Valid @RequestBody VerifyCopy body,HttpServletRequest request) {
        return ApiEnvelope.of(games.returnCopy(id,body.copyCode(),staff,RequestIdFilter.get(request)),
            RequestIdFilter.get(request));
    }

    @PostMapping("/waiter/game-loans/{id}/return-damaged")
    public ApiEnvelope<GameLoanService.LoanView> returnedDamaged(
        @AuthenticationPrincipal StaffPrincipal staff,@PathVariable UUID id,
        @Valid @RequestBody DamagedReturn body,HttpServletRequest request) {
        return ApiEnvelope.of(games.returnDamagedCopy(id,body.copyCode(),
            body.reason(),staff,RequestIdFilter.get(request)),RequestIdFilter.get(request));
    }

    @PostMapping("/waiter/game-loans/{id}/cancel")
    public ApiEnvelope<GameLoanService.LoanView> cancelled(
        @AuthenticationPrincipal StaffPrincipal staff,@PathVariable UUID id,
        HttpServletRequest request) {
        return ApiEnvelope.of(games.cancel(id,staff,RequestIdFilter.get(request)),
            RequestIdFilter.get(request));
    }

    @PostMapping("/admin/games")
    public ApiEnvelope<GameLoanService.GameView> addGame(
        @AuthenticationPrincipal StaffPrincipal staff,@Valid @RequestBody NewGame body,
        HttpServletRequest request) {
        return ApiEnvelope.of(games.addGame(body.name(),body.description(),staff,
            RequestIdFilter.get(request)),RequestIdFilter.get(request));
    }

    @GetMapping("/admin/games/copies")
    public ResponseEntity<ApiEnvelope<List<GameLoanService.CopyView>>> gameCopies(
        @AuthenticationPrincipal StaffPrincipal staff,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(games.copies(),RequestIdFilter.get(request)));
    }

    @GetMapping("/admin/games/condition-history")
    public ResponseEntity<ApiEnvelope<List<GameLoanService.ConditionHistoryView>>> history(
        @AuthenticationPrincipal StaffPrincipal staff,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(games.conditionHistory(),RequestIdFilter.get(request)));
    }

    @PostMapping("/admin/games/copies/{id}/condition")
    public ApiEnvelope<GameLoanService.CopyView> changeCondition(
        @AuthenticationPrincipal StaffPrincipal staff,@PathVariable UUID id,
        @Valid @RequestBody ConditionChange body,HttpServletRequest request) {
        return ApiEnvelope.of(games.changeCopyCondition(id,body.condition(),
            body.reason(),staff,RequestIdFilter.get(request)),RequestIdFilter.get(request));
    }

    @PostMapping("/admin/games/{id}/copies")
    public ApiEnvelope<UUID> addCopy(
        @AuthenticationPrincipal StaffPrincipal staff,@PathVariable UUID id,
        @Valid @RequestBody NewCopy body,HttpServletRequest request) {
        return ApiEnvelope.of(games.addCopy(id,body.copyCode(),staff,RequestIdFilter.get(request)),
            RequestIdFilter.get(request));
    }
}
