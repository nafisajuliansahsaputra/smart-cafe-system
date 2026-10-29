package com.natsx.smartcafe.common.events;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One LISTEN connection per API process, not per browser. NOTIFY only wakes
 * waiting readers; durable live_order_signals is always the source of truth.
 * Periodic cursor checks handle lost notifications and subscriber restarts.
 */
@Component
public class LiveChangeWakeup {
    private final DataSource datasource;
    private final boolean enabled;
    private final Object monitor = new Object();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean running;
    private volatile Thread thread;
    private volatile Connection connection;

    public LiveChangeWakeup(DataSource datasource,
                            @Value("${SCS_LIVE_LISTEN_ENABLED:false}") boolean enabled) {
        this.datasource = datasource;
        this.enabled = enabled;
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        running = true;
        thread = Thread.ofVirtual().name("scs-live-pg-listen").start(this::listen);
    }

    public long generation() { return generation.get(); }

    public void await(long before, long millis) throws InterruptedException {
        synchronized (monitor) {
            if (generation.get() == before) monitor.wait(millis);
        }
    }

    private void listen() {
        while (running) {
            try (Connection conn = datasource.getConnection();
                 Statement statement = conn.createStatement()) {
                connection = conn;
                conn.setAutoCommit(true);
                statement.execute("LISTEN scs_order_live");
                PGConnection pg = conn.unwrap(PGConnection.class);
                while (running) {
                    PGNotification[] events = pg.getNotifications(1000);
                    if (events != null && events.length > 0) {
                        synchronized (monitor) {
                            generation.incrementAndGet();
                            monitor.notifyAll();
                        }
                    }
                }
            } catch (Exception ignored) {
                // Missing notifications are recovered by durable cursor checks.
                if (running) {
                    try { Thread.sleep(1500); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                }
            } finally {
                connection = null;
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        Connection current = connection;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
        synchronized (monitor) { monitor.notifyAll(); }
        Thread listener = thread;
        if (listener != null) listener.interrupt();
    }
}
