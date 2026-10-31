package com.natsx.smartcafe.ops;

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
@RequestMapping("/api/v1/ops/cash-variance-reviews")
public class CashVarianceReviewController {
    private final CashVarianceReviewService review;
    public CashVarianceReviewController(CashVarianceReviewService review) {
        this.review=review;
    }

    public record PostReview(@NotBlank String actionCode,
                             @NotBlank @Size(min=10,max=300) String note) {}

    @GetMapping
    public ResponseEntity<ApiEnvelope<CashVarianceReviewService.CasePage>> list(
        @AuthenticationPrincipal StaffPrincipal actor,
        @RequestParam(defaultValue="0") int page,
        HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(review.cases(actor,page),RequestIdFilter.get(request)));
    }

    @GetMapping("/{shiftId}/history")
    public ResponseEntity<ApiEnvelope<List<CashVarianceReviewService.ReviewAction>>> history(
        @PathVariable UUID shiftId,@AuthenticationPrincipal StaffPrincipal actor,
        HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(review.history(actor,shiftId),RequestIdFilter.get(request)));
    }

    @PostMapping("/{shiftId}/actions")
    public ResponseEntity<ApiEnvelope<CashVarianceReviewService.ReviewAction>> add(
        @PathVariable UUID shiftId,@AuthenticationPrincipal StaffPrincipal actor,
        @RequestHeader(value="Idempotency-Key",required=false) String key,
        @Valid @RequestBody PostReview payload,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(review.add(actor,shiftId,payload.actionCode(),
                payload.note(),key,RequestIdFilter.get(request)),RequestIdFilter.get(request)));
    }
}
