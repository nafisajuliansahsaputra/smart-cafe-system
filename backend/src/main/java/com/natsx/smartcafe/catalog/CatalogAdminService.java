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
    private final CatalogImagePolicy imagePolicy;
    public CatalogAdminService(JdbcTemplate jdbc, CatalogImagePolicy imagePolicy) {
        this.jdbc=jdbc;
        this.imagePolicy=imagePolicy;
    }

    public record Category(UUID id,String name,String slug,boolean active) {}
    public record Item(UUID id,UUID categoryId,String name,long priceIdr,String availability,
                       long revision,String description,String imageUrl) {}

    @Transactional(readOnly=true)
    public List<Category> categories() {
        return jdbc.query("SELECT id,name,slug,active FROM menu_categories ORDER BY sort_order,name",
            (rs,n)->new Category(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),rs.getString("name"),
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
                dbUuid(id),name.strip(),slug);
        }catch(DuplicateKeyException ex) { throw conflict("Category slug already exists"); }
        audit(actor,"CATEGORY_CREATED","MENU_CATEGORY",id,requestId);
        return new Category(id,name.strip(),slug,true);
    }

    @Transactional
    public Category setCategoryActive(UUID id,boolean active,StaffPrincipal actor,String requestId) {
        int changed=jdbc.update("UPDATE menu_categories SET active=? WHERE id=?",active,dbUuid(id));
        if(changed==0)throw missing();
        audit(actor,active?"CATEGORY_ENABLED":"CATEGORY_DISABLED","MENU_CATEGORY",id,requestId);
        return jdbc.queryForObject("SELECT id,name,slug,active FROM menu_categories WHERE id=?",
            (rs,n)->new Category(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),rs.getString("name"),
                rs.getString("slug"),rs.getBoolean("active")),dbUuid(id));
    }

    @Transactional(readOnly=true)
    public List<Item> items() {
        return jdbc.query("""
            SELECT id,category_id,name,base_price_idr,availability,revision,description,image_url
            FROM menu_items ORDER BY name,id LIMIT 500
            """,(rs,n)->readItem(rs));
    }

    @Transactional
    public Item createItem(UUID categoryId,String name,long price,String description,
                           String imageUrl,StaffPrincipal actor,String requestId) {
        checkName(name);
        checkPrice(price);
        String safeDescription=validateDescription(description);
        String safeImage=imagePolicy.validate(imageUrl);
        if(categoryId==null)throw invalid("Category required");
        Integer category=jdbc.queryForObject("SELECT count(*) FROM menu_categories WHERE id=?",
            Integer.class,dbUuid(categoryId));
        if(category==null || category==0)throw missing();
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO menu_items(id,category_id,name,description,image_url,base_price_idr,availability)
            VALUES (?,?,?,?,?,?,'HIDDEN')
            """,dbUuid(id),dbUuid(categoryId),name.strip(),
            safeDescription == null || safeDescription.isEmpty() ? null : safeDescription,
            safeImage == null || safeImage.isEmpty() ? null : safeImage,price);
        audit(actor,"MENU_ITEM_CREATED","MENU_ITEM",id,requestId);
        return detail(id);
    }

    @Transactional
    public Item changeItem(UUID id,UUID categoryId,String name,long price,String availability,
                           long expectedRevision,String description,String imageUrl,
                           StaffPrincipal actor,String requestId) {
        checkName(name);
        checkPrice(price);
        String safeDescription=validateDescription(description);
        String safeImage=imagePolicy.validate(imageUrl);
        if(expectedRevision<1 || categoryId==null || availability==null ||
            !List.of("AVAILABLE","SOLD_OUT","HIDDEN").contains(availability))
            throw invalid("Invalid catalog update");
        Integer category=jdbc.queryForObject("SELECT count(*) FROM menu_categories WHERE id=?",
            Integer.class,dbUuid(categoryId));
        if(category==null || category==0)throw missing();
        var vendor=com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc);
        if(vendor==com.natsx.smartcafe.common.db.JdbcSqlDialect.Vendor.MYSQL) {
            // MySQL has no UPDATE ... RETURNING. A conditional update keeps
            // the same optimistic revision check, and the subsequent read
            // sees the winning row within the same @Transactional boundary.
            int changed=jdbc.update("""
                UPDATE menu_items
                SET category_id=?,name=?,base_price_idr=?,availability=?,
                    description=CASE WHEN ? IS NULL THEN description ELSE NULLIF(?, '') END,
                    image_url=CASE WHEN ? IS NULL THEN image_url ELSE NULLIF(?, '') END,
                    revision=revision+1
                WHERE id=? AND revision=?
                """,dbUuid(categoryId),name.strip(),price,availability,
                safeDescription,safeDescription,safeImage,safeImage,dbUuid(id),expectedRevision);
            if(changed!=1) {
                detail(id);
                throw conflict("Item was modified; refresh its revision");
            }
            audit(actor,"MENU_ITEM_CHANGED","MENU_ITEM",id,requestId);
            return detail(id);
        }
        var edited=jdbc.query("""
            UPDATE menu_items
            SET category_id=?,name=?,base_price_idr=?,availability=?,
                description=CASE WHEN CAST(? AS text) IS NULL THEN description
                                 ELSE NULLIF(CAST(? AS text), '') END,
                image_url=CASE WHEN CAST(? AS text) IS NULL THEN image_url
                               ELSE NULLIF(CAST(? AS text), '') END,
                revision=revision+1
            WHERE id=? AND revision=?
            RETURNING id,category_id,name,base_price_idr,availability,revision,description,image_url
            """,(rs,n)->readItem(rs),dbUuid(categoryId),name.strip(),price,availability,
            safeDescription,safeDescription,safeImage,safeImage,dbUuid(id),expectedRevision);
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
            SELECT id,category_id,name,base_price_idr,availability,revision,description,image_url
            FROM menu_items WHERE id=?
            """,(rs,n)->readItem(rs),dbUuid(id));
        if(rows.isEmpty())throw missing();
        return rows.getFirst();
    }

    private Object dbUuid(UUID value) {
        return com.natsx.smartcafe.common.db.JdbcUuid.parameter(
            value,com.natsx.smartcafe.common.db.JdbcSqlDialect.detect(jdbc));
    }

    private Item readItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Item(com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "id"),com.natsx.smartcafe.common.db.JdbcUuid.read(rs, "category_id"),
            rs.getString("name"),rs.getLong("base_price_idr"),
            rs.getString("availability"),rs.getLong("revision"),
            rs.getString("description"),rs.getString("image_url"));
    }

    private void audit(StaffPrincipal actor,String operation,String type,UUID id,String requestId) {
        jdbc.update("""
            INSERT INTO audit_logs(actor_type,actor_id,operation,resource_type,resource_id,correlation_id)
            VALUES ('STAFF',?,?,?,?,?)
            """,dbUuid(actor.staffId()),operation,type,dbUuid(id),requestId);
    }

    private void checkName(String name) {
        if(name==null || name.isBlank() || name.strip().length()>120)
            throw invalid("Name is required (maximum 120 characters)");
    }
    private String validateDescription(String description) {
        if (description == null) return null; // existing clients may omit the optional field
        String value = description.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (value.length() > 500 || value.codePoints()
            .anyMatch(cp -> Character.isISOControl(cp) && cp != '\n')) {
            throw invalid("Description exceeds 500 characters or contains control characters");
        }
        return value;
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
