package com.natsx.smartcafe.common.events;

import com.natsx.smartcafe.common.api.ApiEnvelope;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicit cancellation for pending Servlet async cursor requests.
 *
 * MVC Callable alone releases the servlet worker but doesn't interrupt its
 * waiting task on all disconnect/timeout paths. DeferredResult hooks cancel
 * our virtual thread when the container *reports* completion or failure.
 * Not all proxies detect an idle TCP disconnect immediately, so the 14s
 * service deadline and admission limits remain essential fallbacks.
 */
@Component
public class LiveCursorAsyncBridge {
    private static final long SERVLET_TIMEOUT_MS = 20_000;

    public DeferredResult<ApiEnvelope<LiveCursorService.Cursor>> submit(
        Callable<ApiEnvelope<LiveCursorService.Cursor>> work) {
        return prepare(work).start();
    }

    // Package-private so a deterministic test can exercise the exact cancel
    // implementation used by the servlet callbacks without any live sockets.
    Pending prepare(Callable<ApiEnvelope<LiveCursorService.Cursor>> work) {
        return new Pending(work);
    }

    static final class Pending {
        private final Callable<ApiEnvelope<LiveCursorService.Cursor>> work;
        private final DeferredResult<ApiEnvelope<LiveCursorService.Cursor>> response =
            new DeferredResult<>(SERVLET_TIMEOUT_MS);
        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private final AtomicBoolean stopped = new AtomicBoolean();

        private Pending(Callable<ApiEnvelope<LiveCursorService.Cursor>> work) {
            this.work = work;
            response.onCompletion(this::cancel);
            response.onError(error -> cancel());
            response.onTimeout(() -> {
                cancel();
                response.setErrorResult(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Live cursor wait timed out"));
            });
        }

        DeferredResult<ApiEnvelope<LiveCursorService.Cursor>> start() {
            Thread thread = Thread.ofVirtual().name("scs-live-cursor-await").unstarted(() -> {
                try {
                    ApiEnvelope<LiveCursorService.Cursor> result = work.call();
                    if (!stopped.get()) response.setResult(result);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    if (!stopped.get()) {
                        response.setErrorResult(new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE, "Live cursor interrupted"));
                    }
                } catch (Exception ex) {
                    if (!stopped.get()) response.setErrorResult(ex);
                }
            });
            worker.set(thread);
            if (stopped.get()) return response;
            thread.start();
            if (stopped.get()) thread.interrupt();
            return response;
        }

        void cancel() {
            if (!stopped.compareAndSet(false, true)) return;
            Thread thread = worker.get();
            if (thread != null) thread.interrupt();
        }
    }
}
