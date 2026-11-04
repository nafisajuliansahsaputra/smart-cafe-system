package com.natsx.smartcafe.catalog;

import com.natsx.smartcafe.audit.AuditTrail;
import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.StaffPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Multi-group single-select variant CRUD; no historical option deletes. */
@Service
public class VariantCatalogService {
    private final JdbcTemplate jdbc;
    private final AuditTrail audit;
    public VariantCatalogService(JdbcTemplate jdbc,AuditTrail audit) {
        this.jdbc=jdbc;
        this.audit=audit;
    }

    public record Option(UUID id,String key,String label,long priceDeltaIdr,
                         boolean active,long revision) {}
    public record Group(UUID id,UUID itemId,String name,boolean required,boolean active,
                        long revision,List<Option> options) {}

    @Transactional(readOnly=true)
    public Group publicGroup(UUID itemId) {
        var groups=publicGroups(itemId);
        if(groups.size()>1)throw conflict("This menu has multiple groups; use /variant-groups");
        return groups.isEmpty()?null:groups.getFirst();
    }

    @Transactional(readOnly=true)
    public List<Group> publicGroups(UUID itemId) {
        Integer live=jdbc.queryForObject("""
            SELECT count(*) FROM menu_items i
            JOIN menu_categories c ON c.id=i.category_id
            JOIN item_availability a ON a.menu_item_id=i.id
            WHERE i.id=? AND i.availability='AVAILABLE' AND c.active
              AND a.available AND (a.limited_quantity IS NULL OR a.limited_quantity>0)
            """,Integer.class,itemId);
        if(live==null || live==0)throw missing();
        return lookupAll(itemId,true);
    }

    @Transactional(readOnly=true)
    public Group adminGroup(UUID itemId) {
        var groups=adminGroups(itemId);
        return groups.isEmpty()?null:groups.getFirst();
    }

    @Transactional(readOnly=true)
    public List<Group> adminGroups(UUID itemId) {
        Integer exists=jdbc.queryForObject("SELECT count(*) FROM menu_items WHERE id=?",
            Integer.class,itemId);
        if(exists==null || exists==0)throw missing();
        return lookupAll(itemId,false);
    }

    private List<Group> lookupAll(UUID itemId,boolean publicOnly) {
        var groups=jdbc.query("""
            SELECT id,name,required,active,revision FROM menu_variant_groups
            WHERE menu_item_id=?
            """+(publicOnly?" AND active=true":"")+" ORDER BY name,id",
            (rs,n)->new Group(rs.getObject("id",UUID.class),itemId,
                rs.getString("name"),rs.getBoolean("required"),
                rs.getBoolean("active"),rs.getLong("revision"),List.of()),itemId);
        var results=new java.util.ArrayList<Group>();
        for(var group:groups) {
            var options=jdbc.query("""
                SELECT id,option_key,label,price_delta_idr,active,revision
                FROM menu_variant_options WHERE group_id=?
                """+(publicOnly?" AND active=true":"")+" ORDER BY label,id",
                (rs,n)->new Option(rs.getObject("id",UUID.class),rs.getString("option_key"),
                    rs.getString("label"),rs.getLong("price_delta_idr"),rs.getBoolean("active"),
                    rs.getLong("revision")),group.id());
            results.add(new Group(group.id(),itemId,group.name(),group.required(),
                group.active(),group.revision(),options));
        }
        return List.copyOf(results);
    }

    private Group byId(UUID groupId) {
        UUID itemId=jdbc.query("SELECT menu_item_id FROM menu_variant_groups WHERE id=?",
            rs->rs.next()?rs.getObject(1,UUID.class):null,groupId);
        if(itemId==null)throw missing();
        return adminGroups(itemId).stream().filter(g->g.id().equals(groupId))
            .findFirst().orElseThrow(()->missing());
    }

    private void lockItem(UUID itemId) {
        UUID id=jdbc.query("SELECT id FROM menu_items WHERE id=? FOR UPDATE",
            rs->rs.next()?rs.getObject(1,UUID.class):null,itemId);
        if(id==null)throw missing();
    }

    @Transactional
    public Group createGroup(UUID itemId,String name,boolean required,
                             StaffPrincipal staff,String requestId) {
        validateLabel(name);
        lockItem(itemId);
        Integer count=jdbc.queryForObject("SELECT count(*) FROM menu_variant_groups WHERE menu_item_id=?",
            Integer.class,itemId);
        if(count!=null && count>=8)throw invalid("Maximum eight modifier groups per menu item");
        UUID groupId=UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO menu_variant_groups(id,menu_item_id,name,required)
                VALUES (?,?,?,?)
                """,groupId,itemId,name.strip(),required);
        }catch(DuplicateKeyException ex){throw conflict("Item already has a variant group");}
        audit.staff(staff.staffId(),"VARIANT_GROUP_CREATED","MENU_ITEM",itemId,requestId);
        return byId(groupId);
    }

    @Transactional
    public Group editGroup(UUID groupId,String name,boolean required,boolean active,long revision,
                           StaffPrincipal staff,String requestId) {
        validateLabel(name);
        UUID itemId=jdbc.query("SELECT menu_item_id FROM menu_variant_groups WHERE id=?",
            rs->rs.next()?rs.getObject(1,UUID.class):null,groupId);
        if(itemId==null)throw missing();
        lockItem(itemId);
        int updated;
        try {
            updated=jdbc.update("""
            UPDATE menu_variant_groups SET name=?,required=?,active=?,revision=revision+1
            WHERE id=? AND revision=?
            """,name.strip(),required,active,groupId,revision);
        } catch(DuplicateKeyException ex) {
            throw conflict("A modifier group with this name already exists");
        }
        if(updated!=1)throw conflict("Variant group changed or does not exist");
        audit.staff(staff.staffId(),"VARIANT_GROUP_CHANGED","MENU_ITEM",itemId,requestId);
        return byId(groupId);
    }

    @Transactional
    public Option createOption(UUID groupId,String key,String label,long delta,
                               StaffPrincipal staff,String requestId) {
        validateOption(key,label,delta);
        UUID itemId=jdbc.query("SELECT menu_item_id FROM menu_variant_groups WHERE id=?",
            rs->rs.next()?rs.getObject(1,UUID.class):null,groupId);
        if(itemId==null)throw missing();
        lockItem(itemId);
        UUID optionId=UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO menu_variant_options(id,group_id,option_key,label,price_delta_idr)
                VALUES (?,?,?,?,?)
                """,optionId,groupId,key,label.strip(),delta);
        }catch(DuplicateKeyException ex){throw conflict("Variant key already exists");}
        audit.staff(staff.staffId(),"VARIANT_OPTION_CREATED","MENU_ITEM",itemId,requestId);
        return option(optionId);
    }

    @Transactional
    public Option editOption(UUID optionId,String label,long delta,boolean active,long revision,
                             StaffPrincipal staff,String requestId) {
        validateLabel(label);
        validateDelta(delta);
        UUID itemId=jdbc.query("""
            SELECT g.menu_item_id FROM menu_variant_options o
            JOIN menu_variant_groups g ON g.id=o.group_id WHERE o.id=?
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,optionId);
        if(itemId==null)throw missing();
        lockItem(itemId);
        int updated=jdbc.update("""
            UPDATE menu_variant_options
            SET label=?,price_delta_idr=?,active=?,revision=revision+1
            WHERE id=? AND revision=?
            """,label.strip(),delta,active,optionId,revision);
        if(updated!=1)throw conflict("Variant option changed or does not exist");
        audit.staff(staff.staffId(),"VARIANT_OPTION_CHANGED","MENU_ITEM",itemId,requestId);
        return option(optionId);
    }

    private Option option(UUID id) {
        return jdbc.queryForObject("""
            SELECT id,option_key,label,price_delta_idr,active,revision
            FROM menu_variant_options WHERE id=?
            """,(rs,n)->new Option(rs.getObject("id",UUID.class),rs.getString("option_key"),
                rs.getString("label"),rs.getLong("price_delta_idr"),
                rs.getBoolean("active"),rs.getLong("revision")),id);
    }

    private void validateOption(String key,String label,long delta) {
        if(key==null || key.length()>60 || !key.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
            throw invalid("Invalid variant key");
        validateLabel(label);
        validateDelta(delta);
    }
    private void validateLabel(String value) {
        if(value==null || value.isBlank() || value.strip().length()>80)
            throw invalid("Variant label is required (max 80 characters)");
    }
    private void validateDelta(long delta) {
        if(delta < -1_000_000_000L || delta > 1_000_000_000L)
            throw invalid("Variant price adjustment is outside supported range");
    }
    private ApiFailureException invalid(String text) {
        return new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",text);
    }
    private ApiFailureException conflict(String text) {
        return new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",text);
    }
    private ApiFailureException missing() {
        return new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND","Menu not found");
    }
}
