package com.natsx.smartcafe.catalog;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Validates a single selected option against the owning item's active group. */
@Service
public class VariantPricingService {
    private final JdbcTemplate jdbc;
    public VariantPricingService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Choice(UUID id,String groupName,String optionKey,String label,long deltaIdr) {}
    private record Group(UUID id,String name,boolean required) {}

    /**
     * Reads locks in the parent-item -> group -> option order. Call from a transaction
     * after the menu item row has been locked for shared pricing stability.
     */
    public Choice resolve(UUID menuItemId,UUID selectedOptionId) {
        List<Group> groups=jdbc.query("""
            SELECT id,name,required FROM menu_variant_groups
            WHERE menu_item_id=? AND active=true FOR SHARE
            """,(rs,n)->new Group(rs.getObject("id",UUID.class),rs.getString("name"),
                rs.getBoolean("required")),menuItemId);
        if(groups.isEmpty()) {
            if(selectedOptionId!=null)throw invalid();
            return null;
        }
        Group group=groups.getFirst();
        if(selectedOptionId==null) {
            if(group.required())throw invalid();
            return null;
        }
        List<Choice> found=jdbc.query("""
            SELECT id,option_key,label,price_delta_idr
            FROM menu_variant_options
            WHERE id=? AND group_id=? AND active=true FOR SHARE
            """,(rs,n)->new Choice(rs.getObject("id",UUID.class),group.name(),
                rs.getString("option_key"),rs.getString("label"),
                rs.getLong("price_delta_idr")),selectedOptionId,group.id());
        if(found.isEmpty())throw invalid();
        return found.getFirst();
    }

    private ApiFailureException invalid() {
        return new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",
            "Selected menu option is unavailable or required");
    }
}
