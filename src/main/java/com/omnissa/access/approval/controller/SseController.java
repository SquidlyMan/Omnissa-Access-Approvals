package com.omnissa.access.approval.controller;

import com.omnissa.access.approval.model.CalloutRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/approvals")
public class SseController {

    private static final Logger logger = LoggerFactory.getLogger(SseController.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Set once shutdown has begun; a stream opened after that is completed at once. */
    private volatile boolean shuttingDown;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        if (shuttingDown) {
            // A tab reconnecting between the close event and Tomcat pausing its
            // connector can still get here. An emitter completed before it is
            // returned ends the request as soon as MVC takes it over, so it
            // never holds the shutdown phase (see closeStreamsForShutdown).
            emitter.complete();
            return emitter;
        }
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        if (shuttingDown) {
            // Lost the race with the close event after the add: finish it here
            // rather than leave it as the one stream the shutdown waits for.
            emitter.complete();
        }
        return emitter;
    }

    /**
     * Ends every open stream the moment shutdown begins.
     *
     * <p>Spring Boot's default shutdown is graceful: Tomcat stops accepting
     * connections and then waits, up to {@code spring.lifecycle.timeout-per-shutdown-phase}
     * (30 s), for in-flight requests to finish. A live-update stream never
     * finishes on its own — it is an emitter with no timeout, held open for as
     * long as a Dashboard or Queue tab is — so with one tab open the JVM sat
     * out the whole phase (measured on 1.23.4: 32 s from SIGTERM to exit, under
     * a second with no tab) and Docker's default 10 s stop timeout killed the
     * container every time.
     *
     * <p>{@link ContextClosedEvent} is published before the lifecycle processor
     * stops the web server's graceful-shutdown lifecycle, so completing the
     * emitters here leaves the phase nothing to wait for. The browser's
     * {@code EventSource} reopens the stream on its own once the new container
     * answers; anything that changed in between shows on the next event or
     * page load.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeStreamsForShutdown() {
        shuttingDown = true;
        int open = emitters.size();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                // Already gone; its completion callback removes it below anyway.
            }
        }
        emitters.clear();
        if (open > 0) {
            logger.info("Closed {} live-update stream(s) for shutdown", open);
        }
    }

    /** Streams currently held open — for tests and diagnostics. */
    int openStreams() {
        return emitters.size();
    }

    public void publishNewRequest(CalloutRequest request) {
        List<SseEmitter> dead = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name("new-request")
                    .data(request));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    public void publishQueueUpdate(String eventName) {
        List<SseEmitter> dead = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(""));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }
}
