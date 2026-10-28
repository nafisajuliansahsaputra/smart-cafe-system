package com.natsx.smartcafe.catalog;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Every active group allows at most one selected option. Required groups demand one.
 * Lock ordering is stable to avoid deadlocks on simultaneous catalog updates.
 */
@Service
public class VariantPricingService {
    private static final int MAX_GROUPS = 8;
    private final JdbcTemplate jdbc;

    public VariantPricingService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Choice(UUID id,UUID groupId,String groupName,String optionKey,
                         String label,long deltaIdr) {}
    public record Selection(List<Choice> choices,long totalDeltaIdr,String label,
                            String uuidArrayLiteral) {}
    private record Group(UUID id,String name,boolean required) {}

    /**
     * Call from an existing transaction holding a shared menu-item price lock.
     * Catalog writers lock that same menu item before modifying option/group rows.
     */
    public Selection resolveSelections(UUID menuItemId,List<UUID> requested) {
        List<UUID> ids=requested==null?List.of():requested;
        if(ids.size()>MAX_GROUPS || ids.stream().anyMatch(x->x==null)
            || new HashSet<>(ids).size()!=ids.size())throw invalid();

        var groups=jdbc.query("""
            SELECT id,name,required FROM menu_variant_groups
            WHERE menu_item_id=? AND active=true ORDER BY id FOR SHARE
            """,(rs,n)->new Group(rs.getObject("id",UUID.class),
                rs.getString("name"),rs.getBoolean("required")),menuItemId);
        if(groups.size()>MAX_GROUPS)throw invalid();

        var groupIndex=new HashMap<UUID,Group>();
        for(var group:groups)groupIndex.put(group.id(),group);

        var selected=new HashMap<UUID,Choice>();
        // Stable ordering of acquired option locks.
        for(var id:ids.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            var rows=jdbc.query("""
                SELECT o.id,o.group_id,o.option_key,o.label,o.price_delta_idr
                FROM menu_variant_options o
                WHERE o.id=? AND o.active=true FOR SHARE OF o
                """,(rs,n)->new Option(rs.getObject("id",UUID.class),
                    rs.getObject("group_id",UUID.class),rs.getString("option_key"),
                    rs.getString("label"),rs.getLong("price_delta_idr")),id);
            if(rows.size()!=1)throw invalid();
            var option=rows.getFirst();
            var group=groupIndex.get(option.groupId());
            if(group==null || selected.containsKey(group.id()))throw invalid();
            selected.put(group.id(),new Choice(option.id(),group.id(),group.name(),
                option.key(),option.label(),option.delta()));
        }
        for(var group:groups)if(group.required()&&!selected.containsKey(group.id()))
            throw invalid();

        var choices=new ArrayList<Choice>();
        for(var group:groups)if(selected.containsKey(group.id()))choices.add(selected.get(group.id()));
        long sum=0;
        try {
            for(var choice:choices)sum=Math.addExact(sum,choice.deltaIdr());
        }catch(ArithmeticException ex){throw invalid();}
        String label=String.join(", ",choices.stream().map(Choice::label).toList());
        String arrayLiteral="{"+String.join(",",choices.stream()
            .map(choice->choice.id().toString()).toList())+"}";
        return new Selection(List.copyOf(choices),sum,label,arrayLiteral);
    }

    public Selection resolveSelections(UUID menuItemId,UUID legacyOptionId) {
        return resolveSelections(menuItemId,legacyOptionId==null?List.of():List.of(legacyOptionId));
    }

    private record Option(UUID id,UUID groupId,String key,String label,long delta) {}

    private ApiFailureException invalid() {
        return new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",
            "Missing, duplicate or unavailable menu modifier selection");
    }
}
