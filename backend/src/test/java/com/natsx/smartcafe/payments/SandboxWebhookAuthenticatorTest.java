package com.natsx.smartcafe.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.natsx.smartcafe.common.api.ApiFailureException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SandboxWebhookAuthenticatorTest {
    private static final String SECRET=uniqueTestKey();
    private static String uniqueTestKey() {
        byte[] key=new byte[48];
        new java.security.SecureRandom().nextBytes(key);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }
    private static final Instant NOW=Instant.parse("2026-10-31T12:00:00Z");
    private final SandboxWebhookAuthenticator verifier=new SandboxWebhookAuthenticator(
        Clock.fixed(NOW,ZoneOffset.UTC),SECRET);

    private String sign(String timestamp,byte[] body) throws Exception {
        Mac hmac=Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        hmac.update((timestamp+".").getBytes(StandardCharsets.US_ASCII));
        return "sha256="+HexFormat.of().formatHex(hmac.doFinal(body));
    }

    private void denied(int expected,String timestamp,String signature,byte[] body) {
        var ex=assertThrows(ApiFailureException.class,
            ()->verifier.verify(timestamp,signature,body));
        assertEquals(expected,ex.status().value());
    }

    @Test void exactRawBytesAndFreshSignedTimestampAreRequired() throws Exception {
        byte[] original="{\"eventId\":\"fake-example-123\",\"state\":\"PENDING\"}"
            .getBytes(StandardCharsets.UTF_8);
        String timestamp=Long.toString(NOW.getEpochSecond());
        String signature=sign(timestamp,original);
        verifier.verify(timestamp,signature,original);
        denied(401,timestamp,signature,
            "{\"state\":\"PENDING\",\"eventId\":\"fake-example-123\"}"
                .getBytes(StandardCharsets.UTF_8));
        denied(401,timestamp,signature,
            "{\"eventId\":\"fake-example-123\",\"state\":\"SUCCEEDED\"}"
                .getBytes(StandardCharsets.UTF_8));
        denied(401,timestamp,"sha256="+"0".repeat(64),original);
        denied(401,timestamp,signature.toUpperCase(),original);
        denied(401,null,signature,original);
        denied(401,timestamp,null,original);
        denied(401,"not-timestamp",signature,original);
        denied(401,Long.toString(NOW.minusSeconds(301).getEpochSecond()),
            sign(Long.toString(NOW.minusSeconds(301).getEpochSecond()),original),original);
        denied(401,Long.toString(NOW.plusSeconds(301).getEpochSecond()),
            sign(Long.toString(NOW.plusSeconds(301).getEpochSecond()),original),original);
        verifier.verify(Long.toString(NOW.minusSeconds(300).getEpochSecond()),
            sign(Long.toString(NOW.minusSeconds(300).getEpochSecond()),original),original);
        verifier.verify(Long.toString(NOW.plusSeconds(300).getEpochSecond()),
            sign(Long.toString(NOW.plusSeconds(300).getEpochSecond()),original),original);
    }

    @Test void oversizedEmptyAndUnconfiguredCallbacksFailClosed() throws Exception {
        String timestamp=Long.toString(NOW.getEpochSecond());
        denied(400,timestamp,"sha256="+"0".repeat(64),new byte[0]);
        denied(400,timestamp,"sha256="+"0".repeat(64),
            new byte[SandboxWebhookAuthenticator.MAX_BODY_BYTES+1]);
        var missing=new SandboxWebhookAuthenticator(
            Clock.fixed(NOW,ZoneOffset.UTC),"");
        assertEquals(503,assertThrows(ApiFailureException.class,()->missing.verify(
            timestamp,"sha256="+"0".repeat(64),"{}".getBytes(StandardCharsets.UTF_8)))
            .status().value());
        var weak=new SandboxWebhookAuthenticator(Clock.fixed(NOW,ZoneOffset.UTC),"short");
        assertEquals(503,assertThrows(ApiFailureException.class,()->weak.verify(
            timestamp,"sha256="+"0".repeat(64),"{}".getBytes(StandardCharsets.UTF_8)))
            .status().value());
    }
}
