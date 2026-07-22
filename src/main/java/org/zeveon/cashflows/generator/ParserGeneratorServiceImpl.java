package org.zeveon.cashflows.generator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.zeveon.cashflows.config.ImportGeneratorProperties;
import org.zeveon.cashflows.config.ImportParserProperties;
import org.zeveon.cashflows.exception.ConflictException;
import org.zeveon.cashflows.exception.NotFoundException;
import org.zeveon.cashflows.generator.AgentCliRunner.AgentEvent;
import org.zeveon.cashflows.model.dto.GeneratorDtos.GeneratorOptions;
import org.zeveon.cashflows.model.dto.GeneratorDtos.ProviderOption;
import org.zeveon.cashflows.model.dto.GeneratorDtos.StartResponse;
import org.zeveon.cashflows.model.dto.GeneratorDtos.TaskSnapshot;
import org.zeveon.cashflows.service.GeneratorSettingsService;
import org.zeveon.cashflows.statement.StatementParserService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Generates a new statement parser with a coding-agent CLI (FR-4.10). The agent
 * runs in the repo root with write access and creates only
 * {@code parsers/<script>.py}; Java then re-runs the generated parser over the
 * sample PDF itself — the agent's own claim of success is not trusted. A single
 * in-memory task slot (single user, one repo) holds the run; the SSE log lives
 * and dies with it.
 *
 * @author Stanislav Vafin
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParserGeneratorServiceImpl implements ParserGeneratorService {

    private static final Pattern SCRIPT_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{1,40}$");
    private static final Duration FINISHED_TASK_TTL = Duration.ofMinutes(30);
    private static final long SSE_TIMEOUT_MILLIS = 30 * 60_000L;
    private static final int MAX_SAMPLES = 10;

    private final ImportGeneratorProperties properties;
    private final ImportParserProperties parserProperties;
    private final GeneratorSettingsService settingsService;
    private final AgentCliRunner cliRunner;
    private final GeneratorPromptBuilder promptBuilder;
    private final StatementParserService parserService;

    private final AtomicReference<GeneratorTask> current = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "parser-generator");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public GeneratorOptions options() {
        if (!properties.enabled()) {
            return new GeneratorOptions(false, List.of());
        }
        return new GeneratorOptions(true, Arrays.stream(AgentProvider.values())
                .map(this::providerOption)
                .toList());
    }

    /** One sample statement staged for the run: the temp copy + the name the user knows it by. */
    private record Sample(Path copy, String originalName) {
    }

    @Override
    public synchronized StartResponse start(List<MultipartFile> files, String bankName, String scriptName) {
        requireEnabled();
        var running = current.get();
        if (running != null && running.getStatus() == GeneratorTask.Status.RUNNING) {
            throw new ConflictException("A parser generation is already running");
        }
        if (scriptName == null || !SCRIPT_NAME_PATTERN.matcher(scriptName).matches()) {
            throw new ConflictException("Script name must be a slug: lowercase letters, digits, '-' or '_'");
        }
        if (files == null || files.isEmpty()) {
            throw new ConflictException("Attach at least one statement PDF");
        }
        if (files.size() > MAX_SAMPLES) {
            throw new ConflictException("At most %d sample statements per generation".formatted(MAX_SAMPLES));
        }
        for (var file : files) {
            if (file == null || file.isEmpty()) {
                throw new ConflictException("Statement file is empty");
            }
            var originalName = file.getOriginalFilename();
            if (originalName == null || !originalName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                throw new ConflictException("Statement must be a PDF file");
            }
        }
        var scriptFile = scriptName + ".py";
        var scriptPath = repoRoot().resolve(parserProperties.scriptsDir()).resolve(scriptFile);
        if (Files.exists(scriptPath)) {
            throw new ConflictException("Parser '%s-pdf' already exists".formatted(scriptName));
        }
        var settings = settingsService.get();
        var provider = settings.provider();
        var resolved = cliRunner.resolve(properties.cli(provider).command());
        if (!resolved.available()) {
            throw new ConflictException("%s CLI is not available: %s".formatted(provider, resolved.error()));
        }
        var model = provider == AgentProvider.CLAUDE ? settings.claudeModel() : settings.codexModel();

        var samples = new ArrayList<Sample>(files.size());
        try {
            var workDir = repoRoot().resolve(properties.workDir());
            Files.createDirectories(workDir);
            var batch = UUID.randomUUID();
            for (var i = 0; i < files.size(); i++) {
                var copy = workDir.resolve("statement-%s-%d.pdf".formatted(batch, i + 1));
                files.get(i).transferTo(copy.toFile());
                samples.add(new Sample(copy, files.get(i).getOriginalFilename()));
            }
        } catch (IOException e) {
            samples.forEach(sample -> deleteQuietly(sample.copy()));
            throw new RuntimeException("Could not stage the statement PDFs: " + e.getMessage(), e);
        }

        var prompt = promptBuilder.build(repoRoot().resolve(properties.promptFile()),
                bankName, scriptFile, scriptName + "-pdf",
                samples.stream().map(Sample::copy).toList(), repoRoot().resolve(properties.workDir()));
        var command = provider == AgentProvider.CLAUDE
                ? AgentCliRunner.claudeCommand(resolved.command(), model)
                : AgentCliRunner.codexCommand(resolved.command(), model, repoRoot());

        var task = new GeneratorTask(scriptName + "-pdf", scriptFile);
        current.set(task);
        task.append(new AgentEvent(AgentEvent.Kind.INFO,
                "Starting %s%s for bank \"%s\" → parsers/%s (%d sample statement%s)".formatted(
                        provider, model == null || model.isBlank() ? "" : " (" + model + ")",
                        bankName, scriptFile, samples.size(), samples.size() == 1 ? "" : "s")));
        executor.submit(() -> runGeneration(task, command, prompt, List.copyOf(samples), scriptPath));
        return new StartResponse(task.getId());
    }

    @Override
    public Optional<TaskSnapshot> current() {
        return Optional.ofNullable(current.get()).map(GeneratorTask::snapshot);
    }

    @Override
    public SseEmitter events(UUID taskId) {
        var task = requireTask(taskId);
        var emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        task.subscribe(emitter);
        return emitter;
    }

    @Override
    public void cancel(UUID taskId) {
        var task = requireTask(taskId);
        task.requestCancel();
        AgentCliRunner.destroyTree(task.getProcess());
    }

    /** Frees the single task slot a while after the run finished (mirrors PendingImportHolder). */
    @Scheduled(fixedDelay = 600_000)
    public void evictFinished() {
        var task = current.get();
        if (task != null && task.getStatus() != GeneratorTask.Status.RUNNING
                && task.getFinishedAt() != null
                && task.getFinishedAt().isBefore(Instant.now().minus(FINISHED_TASK_TTL))) {
            current.compareAndSet(task, null);
        }
    }

    private void runGeneration(GeneratorTask task, List<String> command, String prompt,
                               List<Sample> samples, Path scriptPath) {
        try {
            var dirtyBefore = gitStatus();
            cliRunner.run(command, repoRoot(), prompt, properties.timeout(),
                    task::append, task::onProcessStart);
            if (task.isCancelRequested()) {
                task.finish(GeneratorTask.Status.CANCELLED, null);
                return;
            }
            if (!Files.isRegularFile(scriptPath)) {
                task.finish(GeneratorTask.Status.FAILED,
                        "Agent finished but did not create parsers/" + task.getScriptFile());
                return;
            }
            // every sample must parse — one odd statement variant failing is a real signal
            for (var sample : samples) {
                task.append(new AgentEvent(AgentEvent.Kind.INFO,
                        "Verifying the generated parser against %s…".formatted(sample.originalName())));
                try {
                    var statement = parserService.parse(task.getParserId(), sample.copy());
                    task.append(new AgentEvent(AgentEvent.Kind.INFO,
                            "✓ %s: %d account(s), %d transaction(s)".formatted(
                                    sample.originalName(),
                                    statement.accounts().size(),
                                    statement.accounts().stream().mapToInt(a -> a.transactions().size()).sum())));
                } catch (RuntimeException e) {
                    task.finish(GeneratorTask.Status.FAILED,
                            "Generated parser failed verification on %s: %s (the script is kept at parsers/%s for inspection)"
                                    .formatted(sample.originalName(), e.getMessage(), task.getScriptFile()));
                    return;
                }
            }
            checkUnexpectedChanges(task, dirtyBefore);
            task.finish(GeneratorTask.Status.SUCCEEDED, null);
        } catch (Exception e) {
            if (task.isCancelRequested()) {
                task.finish(GeneratorTask.Status.CANCELLED, null);
            } else {
                log.error("Parser generation failed", e);
                task.finish(GeneratorTask.Status.FAILED, e.getMessage());
            }
        } finally {
            samples.forEach(sample -> deleteQuietly(sample.copy()));
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete staged PDF {}: {}", path, e.getMessage());
        }
    }

    /** Best-effort: warn about files the agent touched beyond its mandate. */
    private void checkUnexpectedChanges(GeneratorTask task, Set<String> dirtyBefore) {
        var dirtyAfter = gitStatus();
        if (dirtyAfter.isEmpty()) {
            return;
        }
        dirtyAfter.removeAll(dirtyBefore);
        var unexpected = dirtyAfter.stream()
                .map(line -> line.length() > 3 ? line.substring(3).trim() : line)
                .map(path -> path.replace("\"", ""))
                .filter(path -> !path.startsWith(parserProperties.scriptsDir() + "/" + task.getScriptFile())
                        && !path.startsWith(properties.workDir())
                        && !path.startsWith("docs/"))
                .toList();
        if (!unexpected.isEmpty()) {
            task.addWarning("Agent also modified: " + String.join(", ", unexpected));
        }
    }

    private Set<String> gitStatus() {
        try {
            var process = new ProcessBuilder("git", "status", "--porcelain")
                    .directory(repoRoot().toFile())
                    .redirectErrorStream(true)
                    .start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return new HashSet<>();
            }
            return new HashSet<>(output.lines().filter(line -> !line.isBlank()).toList());
        } catch (IOException e) {
            return new HashSet<>();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HashSet<>();
        }
    }

    private ProviderOption providerOption(AgentProvider provider) {
        var cli = properties.cli(provider);
        var resolved = cliRunner.resolve(cli.command());
        return new ProviderOption(provider,
                provider == AgentProvider.CLAUDE ? "Claude Code" : "Codex",
                resolved.available(), resolved.version(), resolved.error(), cli.models());
    }

    private GeneratorTask requireTask(UUID taskId) {
        var task = current.get();
        if (task == null || !task.getId().equals(taskId)) {
            throw new NotFoundException("Generation task %s not found".formatted(taskId));
        }
        return task;
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new ConflictException("Parser generation is disabled in this environment");
        }
    }

    private static Path repoRoot() {
        return Path.of("").toAbsolutePath();
    }
}
