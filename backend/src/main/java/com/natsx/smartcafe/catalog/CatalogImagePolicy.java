package com.natsx.smartcafe.catalog;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Fail-closed Cloudinary asset URL policy. The backend NEVER fetches the URL.
 * Browser image requests cannot become server-side SSRF/proxy requests.
 */
@Component
public class CatalogImagePolicy {
    private final String cloudName;
    private final Pattern assetPath;

    public CatalogImagePolicy(@Value("${SCS_MENU_IMAGE_CLOUD_NAME:}") String configuredCloud) {
        this.cloudName = configuredCloud == null ? "" : configuredCloud.strip();
        if (!cloudName.isEmpty() && !cloudName.matches("[a-z0-9][a-z0-9_-]{1,62}")) {
            throw new IllegalArgumentException("Invalid SCS_MENU_IMAGE_CLOUD_NAME");
        }
        this.assetPath = cloudName.isEmpty() ? null : Pattern.compile(
            "/" + Pattern.quote(cloudName) +
            "/image/upload/(?:v[0-9]{1,12}/)?[A-Za-z0-9][A-Za-z0-9/_-]{0,350}\\.(?:jpg|jpeg|png|webp|avif)");
    }

    /** Null means omit/keep on PATCH; blank means explicitly clear. */
    public String validate(String candidate) {
        if (candidate == null) return null;
        // Reject control bytes before trimming so newline/tab suffixes cannot
        // be normalized into an otherwise approved remote address.
        if (candidate.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Invalid menu image URL");
        }
        String input = candidate.strip();
        if (input.isEmpty()) return "";
        if (cloudName.isEmpty()) {
            throw invalid("Configure an approved image cloud before adding menu photos");
        }
        if (input.length() > 700 || input.codePoints().anyMatch(Character::isISOControl)
            || input.indexOf('\\') >= 0 || input.indexOf('%') >= 0) {
            throw invalid("Invalid menu image URL");
        }
        try {
            URI uri = URI.create(input);
            if (!"https".equals(uri.getScheme()) ||
                !"res.cloudinary.com".equals(uri.getHost()) ||
                uri.getPort() != -1 || uri.getRawUserInfo() != null ||
                uri.getRawQuery() != null || uri.getRawFragment() != null ||
                uri.getRawPath() == null || !assetPath.matcher(uri.getRawPath()).matches() ||
                uri.getRawPath().contains("//") || uri.getRawPath().contains("..") ||
                !input.equals(uri.toASCIIString())) {
                throw invalid("Use an HTTPS image/upload URL from the configured Cloudinary account");
            }
            return input;
        } catch (IllegalArgumentException ex) {
            throw invalid("Invalid or unapproved menu image URL");
        }
    }

    private ApiFailureException invalid(String message) {
        return new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message);
    }
}
