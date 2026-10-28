package com.natsx.smartcafe.catalog;

import com.natsx.smartcafe.common.api.ApiFailureException;
import com.natsx.smartcafe.identity.StaffPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Staff catalog writes: immutable IDs, optimistic revisions and audited changes. */
@Service
public class CatalogAdminService {
    private final JdbcTemplate jdbc;
    public CatalogAdminService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Category(UUID id,String name,String slug,boolean active) {}
    public record Item(UUID id,UUID categoryId,String name,long priceIdr,String availability,
                       long revision) {}

    @Transactional(readOnly=true)
    public List<Category> categories() {
        return jdbc.query("SELECT id,name,slug,active FROM menu_categories ORDER BY sort_order,name",
            (rs,n)->new Category(rs.getObject("id",UUID.class),rs.getString("name"),
                rs.getString("slug"),rs.getBoolean("active")));
    }

    @Transactional
    public Category createCategory(String name,String slug,StaffPrincipal actor,String requestId) {
        checkName(name);
        if(slug==null || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || slug.length()>120)
            throw invalid("Invalid category slug");
        UUID id=UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO menu_categories(id,name,slug) VALUES (?,?,?)",
                id,name.strip(),slug);
        }catch(DuplicateKeyException ex) { throw conflict("Category slug already exists"); }
        audit(actor,"CATEGORY_CREATED","MENU_CATEGORY",id,requestId);
        return new Category(id,name.strip(),slug,true);
    }

    @Transactional
    public Category setCategoryActive(UUID id,boolean active,StaffPrincipal actor,String requestId) {
        int changed=jdbc.update("UPDATE menu_categories SET active=? WHERE id=?",active,id);
        if(changed==0)throw missing();
        audit(actor,active?"CATEGORY_ENABLED":"CATEGORY_DISABLED","MENU_CATEGORY",id,requestId);
        return jdbc.queryForObject("SELECT id,name,slug,active FROM menu_categories WHERE id=?",
            (rs,n)->new Category(rs.getObject("id",UUID.class),rs.getString("name"),
                rs.getString("slug"),rs.getBoolean("active")),id);
    }

    @Transactional(readOnly=true)
    public List<Item> items() {
        return jdbc.query("""
            SELECT id,category_id,name,base_price_idr,availability,revision
            FROM menu_items ORDER BY name,id LIMIT 500
            """,(rs,n)->readItem(rs));
    }

    @Transactional
    public Item createItem(UUID categoryId,String name,long price,StaffPrincipal actor,String requestId) {
        checkName(name);
        checkPrice(price);
        if(categoryId==null)throw invalid("Category required");
        Integer category=jdbc.queryForObject("SELECT count(*) FROM menu_categories WHERE id=?",
            Integer.class,categoryId);
        if(category==null || category==0)throw missing();
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO menu_items(id,category_id,name,base_price_idr,availability)
            VALUES (?,?,?,?,'HIDDEN')
            """,id,categoryId,name.strip(),price);
        audit(actor,"MENU_ITEM_CREATED","MENU_ITEM",id,requestId);
        return detail(id);
    }

    @Transactional
    public Item changeItem(UUID id,UUID categoryId,String name,long price,String availability,
                           long expectedRevision,StaffPrincipal actor,String requestId) {
        checkName(name);
        checkPrice(price);
        if(expectedRevision<1 || categoryId==null || availability==null ||
            !List.of("AVAILABLE","SOLD_OUT","HIDDEN").contains(availability))
            throw invalid("Invalid catalog update");
        Integer category=jdbc.queryForObject("SELECT count(*) FROM menu_categories WHERE id=?",
            Integer.class,categoryId);
        if(category==null || category==0)throw missing();
        var edited=jdbc.query("""
            UPDATE menu_items
            SET category_id=?,name=?,base_price_idr=?,availability=?,revision=revision+1
            WHERE id=? AND revision=?
            RETURNING id,category_id,name,base_price_idr,availability,revision
            """,(rs,n)->readItem(rs),categoryId,name.strip(),price,availability,id,expectedRevision);
        if(edited.isEmpty()){
            detail(id);
            throw conflict("Item was modified; refresh its revision");
        }
        audit(actor,"MENU_ITEM_CHANGED","MENU_ITEM",id,requestId);
        return edited.getFirst();
    }

    @Transactional(readOnly=true)
    public Item detail(UUID id) {
        var rows=jdbc.query("""
            SELECT id,category_id,name,base_price_idr,availability,revision
            FROM menu_items WHERE id=?
            """,(rs,n)->readItem(rs),id);
        if(rows.isEmpty())throw missing();
        return rows.getFirst();
    }

    private Item readItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Item(rs.getObject("id",UUID.class),rs.getObject("category_id",UUID.class),
            rs.getString("name"),rs.getLong("base_price_idr"),
            rs.getString("availability"),rs.getLong("revision"));
    }

    private void audit(StaffPrincipal actor,String operation,String type,UUID id,String requestId) {
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,resource_id,correlation_id)
            VALUES ('STAFF',?,?,?,?,?)
            """,actor.staffId(),operation,type,id,requestId);
    }

    private void checkName(String name) {
        if(name==null || name.isBlank() || name.strip().length()>120)
            throw invalid("Name is required (maximum 120 characters)");
    }
    private void checkPrice(long price) {
        if(price<0 || price>1_000_000_000L)
            throw invalid("Unit price is outside the allowed development range");
    }
    private ApiFailureException invalid(String message) {
        return new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);
    }
    private ApiFailureException conflict(String message) {
        return new ApiFailureException(HttpStatus.CONFLICT,"CONFLICT",message);
    }
    private ApiFailureException missing() {
        return new ApiFailureException(HttpStatus.NOT_FOUND,"NOT_FOUND","Catalog entity not found");
    }
}
