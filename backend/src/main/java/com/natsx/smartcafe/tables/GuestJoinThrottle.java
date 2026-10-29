package com.natsx.smartcafe.tables;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistent fixed-window throttle committed BEFORE attempting to validate the
 * join proof. Caller must throw any 429 OUTSIDE this transaction, otherwise the
 * counters would be rolled back alongside a rejected join.
 *
 * This reads the servlet socket peer, never untrusted X-Forwarded-For.
 * With a BFF, the peer can be the shared web server, so this is an abuse
 * mitigation, not a substitute for trusted-edge per-client rate limits.
 */
@Service
public class GuestJoinThrottle {
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final int MAX_PER_PEER = 1200;
    private static final int MAX_PER_TABLE = 60;
    private static final int MAX_PER_PAIR = 24;
    private final JdbcTemplate jdbc;
    private final byte[] secret;

    public GuestJoinThrottle(JdbcTemplate jdbc,
                             @Value("${SCS_JOIN_THROTTLE_SECRET:}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                "SCS_JOIN_THROTTLE_SECRET must have at least 32 UTF-8 bytes");
        }
        this.jdbc = jdbc;
        this.secret = secret.getBytes(StandardCharsets.UTF_8).clone();
    }

    @Transactional
    public boolean allow(String alias, String remoteAddress) {
        // Fixed bucket names avoid storing public aliases or client IPs in DB.
        String peer = remoteAddress == null || remoteAddress.isBlank()
            ? "unknown" : remoteAddress;
        // Global peer limit is checked FIRST, so random aliases cannot produce
        // unbounded table buckets without also consuming a finite peer quota.
        if (consume("peer:" + peer) > MAX_PER_PEER) return false;
        if (consume("table:" + alias) > MAX_PER_TABLE) return false;
        return consume("pair:" + peer + ":" + alias) <= MAX_PER_PAIR;
    }

    private int consume(String bucket) {
        String key = hmac(bucket);
        return jdbc.queryForObject("""
            INSERT INTO guest_join_throttles (bucket_hash,window_started_at,attempts,updated_at)
            VALUES (?,now(),1,now())
            ON CONFLICT (bucket_hash) DO UPDATE SET
              attempts = CASE
                WHEN guest_join_throttles.window_started_at <= now() - interval '10 minutes'
                  THEN 1 ELSE guest_join_throttles.attempts + 1 END,
              window_started_at = CASE
                WHEN guest_join_throttles.window_started_at <= now() - interval '10 minutes'
                  THEN now() ELSE guest_join_throttles.window_started_at END,
              updated_at=now()
            RETURNING attempts
            """, Integer.class, key);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    @Transactional
    public void cleanExpired() {
        jdbc.update("""
            DELETE FROM guest_join_throttles
            WHERE updated_at < now() - interval '2 days'
            """);
    }
}
