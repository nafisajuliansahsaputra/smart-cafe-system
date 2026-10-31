package com.natsx.smartcafe.payments;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/**
 * Demo-only HMAC-SHA256 over timestamp + "." + exact raw JSON bytes.
 * This is NOT any production provider's signature protocol or proof of money.
 */
public final class SandboxWebhookAuthenticator {
    public static final int MAX_BODY_BYTES=4096;
    private static final Duration MAX_SKEW=Duration.ofMinutes(5);
    private final Clock clock;
    private final byte[] secret;

    public SandboxWebhookAuthenticator(Clock clock,String secret) {
        this.clock=Objects.requireNonNull(clock);
        this.secret=secret==null?new byte[0]:
            secret.getBytes(StandardCharsets.UTF_8).clone();
    }

    public void verify(String timestamp,String signature,byte[] raw) {
        // Never parse or save claims until raw signature and freshness pass.
        if(secret.length<32)throw new ApiFailureException(
            HttpStatus.SERVICE_UNAVAILABLE,"UNAVAILABLE",
            "Sandbox callback verification is not configured");
        if(raw==null||raw.length==0||raw.length>MAX_BODY_BYTES)
            throw new ApiFailureException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",
                "Invalid sandbox callback size");
        if(timestamp==null||!timestamp.matches("[0-9]{10,11}")||
           signature==null||!signature.matches("sha256=[a-f0-9]{64}"))
            throw unauthorized();
        long seconds;
        try {
            seconds=Long.parseLong(timestamp);
        }catch(NumberFormatException ex) {
            throw unauthorized();
        }
        Instant issued=Instant.ofEpochSecond(seconds);
        Instant now=clock.instant();
        if(issued.isBefore(now.minus(MAX_SKEW))||issued.isAfter(now.plus(MAX_SKEW)))
            throw unauthorized();
        // Authenticate the original byte stream incrementally. Never allocate
        // an input-sized combined buffer from an untrusted HTTP body.
        byte[] actual;
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            mac.update((timestamp+".").getBytes(StandardCharsets.US_ASCII));
            actual=mac.doFinal(raw);
        }catch(java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable",ex);
        }
        byte[] provided=HexFormat.of().parseHex(signature.substring(7));
        if(!MessageDigest.isEqual(actual,provided))throw unauthorized();
    }

    private ApiFailureException unauthorized() {
        return new ApiFailureException(HttpStatus.UNAUTHORIZED,
            "UNAUTHENTICATED","Invalid or expired sandbox callback");
    }
}
