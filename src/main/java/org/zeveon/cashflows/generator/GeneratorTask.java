package org.zeveon.cashflows.generator;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.zeveon.cashflows.model.dto.GeneratorDtos.TaskSnapshot;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * State of the single parser-generation run (FR-4.10): a bounded replay buffer
 * of agent events plus live SSE fan-out, so the UI can reattach after a page
 * reload and see the full log. In-memory by design — same philosophy as
 * {@code PendingImportHolder}: single user, a restart costs a retry.
 *
 * @author Stanislav Vafin
 */
@Slf4j
public class GeneratorTask {

    public enum Status {RUNNING, SUCCEEDED, FAILED, CANCELLED}

    private static final int BUFFER_CAP = 2000;

    public record LogEvent(long seq, String kind, String text) {
    }

    @Getter
    private final UUID id = UUID.randomUUID();
    @Getter
    private final String parserId;
    @Getter
    private final String scriptFile;
    private final Instant startedAt = Instant.now();

    private final ArrayDeque<LogEvent> buffer = new ArrayDeque<>();
    private final List<SseEmitter> emitters = new ArrayList<>();
    private long nextSeq;
    private boolean truncated;

    @Getter
    private volatile Status status = Status.RUNNING;
    @Getter
    private volatile String error;
    private final List<String> warnings = new ArrayList<>();
    @Getter
    private volatile Instant finishedAt;
    @Getter
    private volatile Process process;
    @Getter
    private volatile boolean cancelRequested;

    public GeneratorTask(String parserId, String scriptFile) {
        this.parserId = parserId;
        this.scriptFile = scriptFile;
    }

    public void onProcessStart(Process process) {
        this.process = process;
    }

    public synchronized void append(AgentCliRunner.AgentEvent event) {
        var logEvent = new LogEvent(nextSeq++, event.kind().name().toLowerCase(), event.text());
        if (buffer.size() == BUFFER_CAP) {
            buffer.removeFirst();
            if (!truncated) {
                truncated = true;
                buffer.addFirst(new LogEvent(-1, "info", "… earlier log truncated …"));
            }
        }
        buffer.addLast(logEvent);
        emitters.removeIf(emitter -> !send(emitter, "log", logEvent));
    }

    public synchronized void addWarning(String warning) {
        warnings.add(warning);
    }

    /** Replays the buffered log, then keeps streaming; completes immediately if already terminal. */
    public synchronized void subscribe(SseEmitter emitter) {
        for (var event : buffer) {
            if (!send(emitter, "log", event)) {
                return;
            }
        }
        if (status != Status.RUNNING) {
            send(emitter, "status", snapshot());
            emitter.complete();
            return;
        }
        emitters.add(emitter);
        emitter.onCompletion(() -> unsubscribe(emitter));
        emitter.onTimeout(() -> unsubscribe(emitter));
    }

    public synchronized void requestCancel() {
        cancelRequested = true;
    }

    /** Transitions to a terminal state and notifies every live subscriber. */
    public synchronized void finish(Status terminal, String error) {
        if (status != Status.RUNNING) {
            return;
        }
        this.status = terminal;
        this.error = error;
        this.finishedAt = Instant.now();
        var snapshot = snapshot();
        emitters.forEach(emitter -> {
            if (send(emitter, "status", snapshot)) {
                emitter.complete();
            }
        });
        emitters.clear();
    }

    public synchronized TaskSnapshot snapshot() {
        return new TaskSnapshot(id, status.name(), parserId, scriptFile, error,
                List.copyOf(warnings), startedAt, finishedAt);
    }

    private synchronized void unsubscribe(SseEmitter emitter) {
        emitters.remove(emitter);
    }

    private static boolean send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            // client went away — drop the emitter, the buffer keeps the log
            return false;
        }
    }
}
