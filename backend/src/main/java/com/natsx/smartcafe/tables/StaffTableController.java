package com.natsx.smartcafe.tables;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import com.natsx.smartcafe.common.api.RequestIdFilter;
import com.natsx.smartcafe.identity.StaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/waiter/tables")
public class StaffTableController {
    private final StaffTableQueryService query;

    public StaffTableController(StaffTableQueryService query) {
        this.query = query;
    }

    @GetMapping
    public ResponseEntity<ApiEnvelope<List<StaffTableQueryService.TableRow>>> list(
        @AuthenticationPrincipal StaffPrincipal staff, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiEnvelope.of(query.tables(staff), RequestIdFilter.get(request)));
    }
}
