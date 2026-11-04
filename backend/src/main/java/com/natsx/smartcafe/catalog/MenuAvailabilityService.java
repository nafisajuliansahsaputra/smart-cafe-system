package com.natsx.smartcafe.catalog;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.StaffPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuAvailabilityService {
    private final JdbcTemplate jdbc;
    public MenuAvailabilityService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Availability(UUID itemId,boolean available,Long limitedQuantity,long version) {}

    @Transactional(readOnly=true)
    public Availability current(UUID id) {
        var found=jdbc.query("""
            SELECT available,limited_quantity,version FROM item_availability WHERE menu_item_id=?
            """,(rs,n)->new Availability(id,rs.getBoolean("available"),
                rs.getObject("limited_quantity")==null?null:rs.getLong("limited_quantity"),
                rs.getLong("version")),id);
        if(found.isEmpty())throw notFound();
        return found.getFirst();
    }

    @Transactional
    public Availability set(UUID itemId,boolean available,Long quantity,
                             long expectedVersion,String reason,StaffPrincipal staff,String requestId) {
        if(expectedVersion<0 || quantity!=null && quantity<0)
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Stock quantity or expected version is invalid");
        var changed=jdbc.query("""
            UPDATE item_availability
            SET available=?, limited_quantity=?, version=version+1
            WHERE menu_item_id=? AND version=?
            RETURNING available,limited_quantity,version
            """,(rs,n)->new Availability(itemId,rs.getBoolean("available"),
                rs.getObject("limited_quantity")==null?null:rs.getLong("limited_quantity"),
                rs.getLong("version")),available,quantity,itemId,expectedVersion);
        if(changed.isEmpty()) {
            current(itemId); // Differentiates missing catalog item from stale version.
            throw new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",
                "Inventory has changed; fetch the latest version");
        }
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,
                                   resource_id,reason,correlation_id)
            VALUES ('STAFF',?,'MENU_STOCK_UPDATED','MENU_ITEM',?,?,?)
            """,staff.staffId(),itemId,reason,requestId);
        return changed.getFirst();
    }

    private ApiFailureException notFound() {
        return new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND","Menu item not found");
    }
}
