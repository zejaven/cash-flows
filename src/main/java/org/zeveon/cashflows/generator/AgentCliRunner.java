package org.zeveon.cashflows.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs a coding-agent CLI ({@code claude} / {@code codex}) as a subprocess for
 * parser generation (FR-4.10). Windows-specific care: npm ships the CLIs as
 * {@code .cmd} shims, so the base command is resolved by probing
 * {@code --version} over candidate names; on kill, process descendants are
 * destroyed first — destroying only the shim leaves the real node process
 * running. The prompt travels over stdin so it never hits the command line.
 *
 * @author Stanislav Vafin
 */
@Slf4j
@Component
public class AgentCliRunner {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final int STDERR_TAIL_LINES = 50;

    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable, "agent-cli-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ResolvedCommand> resolved = new ConcurrentHashMap<>();

    public record ResolvedCommand(boolean available, String command, String version, String error) {
    }

    public record AgentEvent(Kind kind, String text) {

        public enum Kind {TEXT, ACTIVITY, INFO, ERROR}
    }

    /**
     * Probes {@code [command, command.cmd, command.exe]} with {@code --version}
     * and caches the first name that exits 0.
     */
    public ResolvedCommand resolve(String command) {
        return resolved.computeIfAbsent(command, this::probe);
    }

    /** claude -p, streaming JSON, file writes allowed (single-user local app). */
    public static List<String> claudeCommand(String resolvedCommand, String model) {
        var command = new ArrayList<>(List.of(resolvedCommand, "-p",
                "--output-format", "stream-json", "--verbose", "--include-partial-messages",
                "--permission-mode", "bypassPermissions"));
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }
        return command;
    }

    /** codex exec, sandboxed to the workspace, prompt read from stdin ({@code -}). */
    public static List<String> codexCommand(String resolvedCommand, String model, Path repoRoot) {
        var command = new ArrayList<>(List.of(resolvedCommand, "-a", "never", "exec",
                "--json", "--ephemeral", "--sandbox", "workspace-write"));
        if (model != null && !model.isBlank()) {
            command.add("--model");
            command.add(model);
        }
        command.add("-C");
        command.add(repoRoot.toString());
        command.add("-");
        return command;
    }

    /**
     * Runs the CLI to completion, streaming normalized events into {@code sink}.
     * The live {@link Process} is handed to {@code onStart} so the caller can
     * cancel from another thread.
     *
     * @throws IllegalStateException non-zero exit or timeout, with a bounded stderr tail
     */
    public void run(List<String> command, Path cwd, String prompt, Duration timeout,
                    Consumer<AgentEvent> sink, Consumer<Process> onStart) {
        Process process = null;
        var timedOut = new AtomicBoolean(false);
        try {
            var builder = new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .redirectErrorStream(false);
            // the agent runs the parser itself; keep its Python output UTF-8 on Windows
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            process = builder.start();
            onStart.accept(process);

            var watched = process;
            var kill = WATCHDOG.schedule(() -> {
                timedOut.set(true);
                destroyTree(watched);
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);

            try (var stdin = process.getOutputStream()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            var stderrTail = drainStderr(process.getErrorStream());
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                var normalizer = new Normalizer();
                String line;
                while ((line = reader.readLine()) != null) {
                    normalizer.accept(line, sink);
                }
            }
            process.waitFor();
            kill.cancel(false);
            if (timedOut.get()) {
                throw new IllegalStateException("Agent timed out after " + timeout);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Agent exited with code %d: %s"
                        .formatted(process.exitValue(), String.join("\n", stderrTail)));
            }
        } catch (IOException e) {
            destroyTree(process);
            throw new IllegalStateException("Could not run agent CLI: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            destroyTree(process);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent run interrupted", e);
        }
    }

    /** Kills the whole process tree — descendants first (see class Javadoc). */
    public static void destroyTree(Process process) {
        if (process == null) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private ResolvedCommand probe(String command) {
        String lastError = null;
        for (var candidate : List.of(command, command + ".cmd", command + ".exe")) {
            try {
                var process = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true)
                        .start();
                var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    lastError = candidate + ": --version timed out";
                    continue;
                }
                if (process.exitValue() == 0) {
                    return new ResolvedCommand(true, candidate, output.strip(), null);
                }
                lastError = candidate + ": --version exited with " + process.exitValue();
            } catch (IOException e) {
                lastError = candidate + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ResolvedCommand(false, command, null, "probe interrupted");
            }
        }
        return new ResolvedCommand(false, command, null, lastError);
    }

    /** stderr may echo statement content — keep it in memory only, never in logs. */
    private ArrayDeque<String> drainStderr(InputStream stream) {
        var tail = new ArrayDeque<String>();
        var thread = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (tail) {
                        if (tail.size() == STDERR_TAIL_LINES) {
                            tail.removeFirst();
                        }
                        tail.addLast(line);
                    }
                }
            } catch (IOException ignored) {
                // process died — the tail keeps whatever was read
            }
        }, "agent-cli-stderr");
        thread.setDaemon(true);
        thread.start();
        return tail;
    }

    /**
     * Folds each provider's stream-JSON into {@link AgentEvent}s: Claude
     * {@code stream_event} text deltas become TEXT chunks (with the final
     * {@code result} re-emitted only if nothing streamed), tool/command
     * activity becomes ACTIVITY lines, failures become ERROR. Unparseable
     * lines pass through as ACTIVITY.
     */
    private final class Normalizer {

        private boolean anyTextDelta;

        void accept(String line, Consumer<AgentEvent> sink) {
            if (line.isBlank()) {
                return;
            }
            JsonNode node;
            try {
                node = objectMapper.readTree(line);
            } catch (IOException e) {
                sink.accept(new AgentEvent(AgentEvent.Kind.ACTIVITY, line));
                return;
            }
            switch (node.path("type").asText()) {
                // Claude stream-json
                case "system" -> sink.accept(new AgentEvent(AgentEvent.Kind.INFO,
                        "session started (model %s)".formatted(node.path("model").asText("?"))));
                case "stream_event" -> claudeStreamEvent(node.path("event"), sink);
                case "assistant" -> claudeAssistant(node.path("message"), sink);
                case "result" -> {
                    if (!anyTextDelta && node.hasNonNull("result")) {
                        sink.accept(new AgentEvent(AgentEvent.Kind.TEXT, node.path("result").asText()));
                    }
                    if (node.path("is_error").asBoolean(false)) {
                        sink.accept(new AgentEvent(AgentEvent.Kind.ERROR, node.path("result").asText("agent error")));
                    }
                }
                // Codex --json
                case "item.completed" -> codexItem(node.path("item"), sink);
                case "turn.failed", "error" -> sink.accept(new AgentEvent(AgentEvent.Kind.ERROR,
                        node.path("error").path("message").asText(node.toString())));
                default -> {
                    // heartbeat/user/turn.* noise — ignore
                }
            }
        }

        private void claudeStreamEvent(JsonNode event, Consumer<AgentEvent> sink) {
            switch (event.path("type").asText()) {
                case "content_block_delta" -> {
                    var text = event.path("delta").path("text").asText("");
                    if (!text.isEmpty()) {
                        anyTextDelta = true;
                        sink.accept(new AgentEvent(AgentEvent.Kind.TEXT, text));
                    }
                }
                case "content_block_start" -> {
                    var block = event.path("content_block");
                    if ("tool_use".equals(block.path("type").asText())) {
                        sink.accept(new AgentEvent(AgentEvent.Kind.ACTIVITY,
                                "→ " + block.path("name").asText("tool")));
                    }
                }
                default -> {
                    // message_start/stop etc — ignore
                }
            }
        }

        private void claudeAssistant(JsonNode message, Consumer<AgentEvent> sink) {
            for (var block : message.path("content")) {
                if ("tool_use".equals(block.path("type").asText())) {
                    var input = block.path("input");
                    var detail = input.hasNonNull("file_path") ? input.path("file_path").asText()
                            : input.hasNonNull("command") ? input.path("command").asText()
                            : "";
                    sink.accept(new AgentEvent(AgentEvent.Kind.ACTIVITY,
                            ("→ " + block.path("name").asText("tool") + " " + detail).stripTrailing()));
                }
            }
        }

        private void codexItem(JsonNode item, Consumer<AgentEvent> sink) {
            switch (item.path("type").asText()) {
                case "agent_message" -> sink.accept(new AgentEvent(AgentEvent.Kind.TEXT,
                        item.path("text").asText() + "\n"));
                case "command_execution" -> sink.accept(new AgentEvent(AgentEvent.Kind.ACTIVITY,
                        "$ " + item.path("command").asText()));
                case "file_change" -> sink.accept(new AgentEvent(AgentEvent.Kind.ACTIVITY,
                        "✎ " + item.path("path").asText(item.toString())));
                default -> {
                    // reasoning etc — ignore
                }
            }
        }
    }
}
