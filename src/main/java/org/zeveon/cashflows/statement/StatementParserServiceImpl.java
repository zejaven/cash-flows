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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs the per-bank statement parser as a subprocess (design decision: the
 * Python/pdfplumber parser is battle-tested and is not ported to Java). The
 * registry of parsers is code: parserId -> script; currently one entry.
 *
 * @author Stanislav Vafin
 */
@Slf4j
@Service
public class StatementParserServiceImpl implements StatementParserService {

    public static final String ALATAU_PARSER_ID = "alatau-pdf";

    private final ImportParserProperties properties;
    private final ObjectMapper objectMapper;

    public StatementParserServiceImpl(ImportParserProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** parserId -> script path. Extend here when new bank parsers appear (FR-4.2). */
    private Map<String, String> registry() {
        return Map.of(ALATAU_PARSER_ID, properties.scriptPath());
    }

    @Override
    public StatementDto parse(String parserId, Path pdf) {
        var script = registry().get(parserId);
        if (script == null) {
            throw new ConflictException("Unknown statement parser: " + parserId);
        }
        try {
            var builder = new ProcessBuilder(properties.pythonCommand(), script, pdf.toString())
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
