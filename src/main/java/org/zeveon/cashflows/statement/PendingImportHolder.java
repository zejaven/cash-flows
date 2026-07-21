package org.zeveon.cashflows.statement;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Parsed statements between preview and confirm. In-memory by design: single
 * user, payloads are hundreds of KB, and losing one on restart costs a repeat
 * upload (design decision 8).
 *
 * @author Stanislav Vafin
 */
@Component
public class PendingImportHolder {

    public record PendingImport(Long bankId, String fileName, StatementDto statement, Instant createdAt) {
    }

    private static final Duration TTL = Duration.ofMinutes(30);

    private final Map<UUID, PendingImport> pending = new ConcurrentHashMap<>();

    public UUID put(Long bankId, String fileName, StatementDto statement) {
        var token = UUID.randomUUID();
        pending.put(token, new PendingImport(bankId, fileName, statement, Instant.now()));
        return token;
    }

    public Optional<PendingImport> get(UUID token) {
        return Optional.ofNullable(pending.get(token))
                .filter(entry -> entry.createdAt().isAfter(Instant.now().minus(TTL)));
    }

    public void remove(UUID token) {
        pending.remove(token);
    }

    @Scheduled(fixedDelay = 600_000)
    public void evictExpired() {
        var cutoff = Instant.now().minus(TTL);
        pending.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
    }
}
