package com.natsx.smartcafe;

import static org.junit.jupiter.api.Assertions.*;

import com.natsx.smartcafe.catalog.CatalogImagePolicy;
import com.natsx.smartcafe.common.api.ApiFailureException;
import org.junit.jupiter.api.Test;

/** Pure validation: no Cloudinary request, account secret or production resource. */
class CatalogImagePolicyTest {
    private static final String GOOD =
        "https://res.cloudinary.com/scs-e2e/image/upload/v1234567890/menu/latte.webp";

    @Test void acceptsOnlyConfiguredCloudAndUploadedRasterImage() {
        CatalogImagePolicy policy=new CatalogImagePolicy("scs-e2e");
        assertEquals(GOOD,policy.validate(GOOD));
        assertEquals("",policy.validate("   "));
        assertNull(policy.validate(null));
        for(String malicious:new String[]{
            "http://res.cloudinary.com/scs-e2e/image/upload/v123/menu/latte.jpg",
            "https://res.cloudinary.com/other-cloud/image/upload/menu/latte.jpg",
            "https://res.cloudinary.com.evil.example/scs-e2e/image/upload/a.jpg",
            "https://127.0.0.1/scs-e2e/image/upload/a.jpg",
            "https://res.cloudinary.com/scs-e2e/image/fetch/http://127.0.0.1/a.jpg",
            "https://res.cloudinary.com/scs-e2e/raw/upload/evil.svg",
            "https://res.cloudinary.com/scs-e2e/image/upload/demo.svg",
            "https://res.cloudinary.com/scs-e2e/image/upload/demo.pdf",
            "https://user:pass@res.cloudinary.com/scs-e2e/image/upload/a.png",
            "https://res.cloudinary.com:444/scs-e2e/image/upload/a.png",
            "https://res.cloudinary.com/scs-e2e/image/upload/a.png?redirect=x",
            "https://res.cloudinary.com/scs-e2e/image/upload/a.png#token",
            "https://res.cloudinary.com/scs-e2e/image/upload/%2e%2e/a.jpg",
            "https://res.cloudinary.com/scs-e2e/image/upload/a/../b.jpg",
            "https://res.cloudinary.com/scs-e2e/image/upload//a.jpg",
            "https://res.cloudinary.com/scs-e2e/image/upload/a.jpg\n",
            "javascript:alert(1)",
            "data:image/svg+xml,<svg/onload=alert(1)>",
        }) {
            assertThrows(ApiFailureException.class,()->policy.validate(malicious),
                "A menu image must never resolve to an unapproved origin: "+malicious);
        }
    }

    @Test void disabledByDefaultUnlessOperatorSetsTheExactCloud() {
        CatalogImagePolicy policy=new CatalogImagePolicy("");
        assertNull(policy.validate(null));
        assertEquals("",policy.validate(""));
        assertThrows(ApiFailureException.class,()->policy.validate(GOOD));
        assertThrows(IllegalArgumentException.class,()->new CatalogImagePolicy("Cloud/MyTenant"));
    }
}
