package org.zeveon.cashflows.statement;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.zeveon.cashflows.config.ImportParserProperties;
import org.zeveon.cashflows.exception.ConflictException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs a loan payment-schedule parser as a subprocess (FR-13.5) — the slim
 * sibling of {@link StatementParserServiceImpl} over its own scripts directory
 * ({@code parsers/schedules}), same registry-by-naming-convention and the same
 * exit-code protocol: 1 = the schedule failed its own arithmetic verification.
 *
 * @author Stanislav Vafin
 */
@Slf4j
@Service
public class LoanScheduleParserServiceImpl implements LoanScheduleParserService {

    private static final Pattern STEM_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]*$");

    private final ImportParserProperties properties;
    private final ObjectMapper objectMapper;

    public LoanScheduleParserServiceImpl(ImportParserProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public LoanScheduleDto parse(String parserId, Path file) {
        var script = resolveScript(parserId);
        try {
            var builder = new ProcessBuilder(properties.pythonCommand(), script.toString(), file.toString())
                    .redirectErrorStream(false);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            var process = builder.start();
            var stdout = readAsync(process.getInputStream());
            var stderr = readAsync(process.getErrorStream());
            if (!process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Schedule parser timed out after " + properties.timeout());
            }
            var exit = process.exitValue();
            var errText = stderr.join();
            if (exit == 1) {
                throw new StatementVerificationException(mismatchDetails(errText));
            }
            if (exit != 0) {
                log.error("Schedule parser exited with {}: {}", exit, errText);
                throw new RuntimeException("Schedule parser failed (exit %d)".formatted(exit));
            }
            return objectMapper.readValue(stdout.join(), LoanScheduleDto.class);
        } catch (IOException e) {
            throw new RuntimeException("Could not run schedule parser: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Schedule parsing interrupted", e);
        }
    }

    @Override
    public List<ParserInfo> availableParsers() {
        var scriptsDir = Path.of(properties.scheduleScriptsDir());
        if (!Files.isDirectory(scriptsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(scriptsDir)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".py"))
                    .map(name -> name.substring(0, name.length() - ".py".length()))
                    .filter(stem -> STEM_PATTERN.matcher(stem).matches())
                    .sorted()
                    .map(stem -> new ParserInfo(stem, stem + ".py"))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Could not list schedule parser scripts: " + e.getMessage(), e);
        }
    }

    private Path resolveScript(String parserId) {
        if (parserId == null || !STEM_PATTERN.matcher(parserId).matches()) {
            throw new ConflictException("Unknown schedule parser: " + parserId);
        }
        var scriptsDir = Path.of(properties.scheduleScriptsDir()).toAbsolutePath().normalize();
        var script = scriptsDir.resolve(parserId + ".py").normalize();
        if (!script.startsWith(scriptsDir) || !Files.isRegularFile(script)) {
            throw new ConflictException("Unknown schedule parser: " + parserId);
        }
        return script;
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static String mismatchDetails(String stderr) {
        var lines = stderr.lines()
                .filter(line -> line.contains("РАСХОЖДЕНИЕ") || line.contains("MISMATCH"))
                .toList();
        return lines.isEmpty()
                ? "Schedule verification failed"
                : String.join("; ", lines.size() <= 5 ? lines : lines.subList(0, 5));
    }
}
