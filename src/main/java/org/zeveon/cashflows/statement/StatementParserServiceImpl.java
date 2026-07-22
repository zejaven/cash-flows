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
 * Runs the per-bank statement parser as a subprocess (design decision: the
 * Python/pdfplumber parsers are battle-tested and are not ported to Java). The
 * registry of parsers is a naming convention over the scripts directory
 * (FR-4.2): script {@code <stem>.py} ⇄ parser id {@code <stem>-pdf}. Dropping a
 * new script into the directory — by hand or via the AI parser generator
 * (FR-4.10) — makes it available without code changes.
 *
 * @author Stanislav Vafin
 */
@Slf4j
@Service
public class StatementParserServiceImpl implements StatementParserService {

    private static final String PARSER_ID_SUFFIX = "-pdf";
    /** Stems are slugs: no dots, slashes or uppercase — this is also the path-traversal gate. */
    private static final Pattern STEM_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]*$");

    private final ImportParserProperties properties;
    private final ObjectMapper objectMapper;

    public StatementParserServiceImpl(ImportParserProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public StatementDto parse(String parserId, Path pdf) {
        var script = resolveScript(parserId);
        try {
            var builder = new ProcessBuilder(properties.pythonCommand(), script.toString(), pdf.toString())
                    .redirectErrorStream(false);
            // Russian mismatch messages on Windows default to cp1251 otherwise
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            var process = builder.start();
            var stdout = readAsync(process.getInputStream());
            var stderr = readAsync(process.getErrorStream());
            if (!process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("Statement parser timed out after " + properties.timeout());
            }
            var exit = process.exitValue();
            var errText = stderr.join();
            if (exit == 1) {
                throw new StatementVerificationException(mismatchDetails(errText));
            }
            if (exit != 0) {
                log.error("Parser exited with {}: {}", exit, errText);
                throw new RuntimeException("Statement parser failed (exit %d)".formatted(exit));
            }
            return objectMapper.readValue(stdout.join(), StatementDto.class);
        } catch (IOException e) {
            throw new RuntimeException("Could not run statement parser: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Statement parsing interrupted", e);
        }
    }

    @Override
    public List<ParserInfo> availableParsers() {
        var scriptsDir = Path.of(properties.scriptsDir());
        if (!Files.isDirectory(scriptsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(scriptsDir)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".py"))
                    .map(name -> name.substring(0, name.length() - ".py".length()))
                    .filter(stem -> STEM_PATTERN.matcher(stem).matches())
                    .sorted()
                    .map(stem -> new ParserInfo(stem + PARSER_ID_SUFFIX, stem + ".py"))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Could not list parser scripts: " + e.getMessage(), e);
        }
    }

    private Path resolveScript(String parserId) {
        if (parserId == null || !parserId.endsWith(PARSER_ID_SUFFIX)) {
            throw new ConflictException("Unknown statement parser: " + parserId);
        }
        var stem = parserId.substring(0, parserId.length() - PARSER_ID_SUFFIX.length());
        if (!STEM_PATTERN.matcher(stem).matches()) {
            throw new ConflictException("Unknown statement parser: " + parserId);
        }
        var scriptsDir = Path.of(properties.scriptsDir()).toAbsolutePath().normalize();
        var script = scriptsDir.resolve(stem + ".py").normalize();
        if (!script.startsWith(scriptsDir) || !Files.isRegularFile(script)) {
            throw new ConflictException("Unknown statement parser: " + parserId);
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
                ? "Statement checksum verification failed"
                : String.join("; ", trimTo(lines, 5));
    }

    private static List<String> trimTo(List<String> lines, int max) {
        return lines.size() <= max ? lines : lines.subList(0, max);
    }
}
