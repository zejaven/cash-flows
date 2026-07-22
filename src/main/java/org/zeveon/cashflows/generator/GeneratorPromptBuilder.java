package org.zeveon.cashflows.generator;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fills the parser-generation prompt template (FR-4.10). The template is a
 * repo file so the mission text is versioned and reviewable; only paths and
 * identifiers are substituted — statement content never enters the prompt.
 *
 * @author Stanislav Vafin
 */
@Component
public class GeneratorPromptBuilder {

    public String build(Path template, String bankName, String scriptName, String parserId,
                        List<Path> pdfPaths, Path workDir) {
        String text;
        try {
            text = Files.readString(template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read prompt template " + template, e);
        }
        var substitutions = Map.of(
                "{{bank_name}}", bankName,
                "{{script_name}}", scriptName,
                "{{parser_id}}", parserId,
                "{{pdf_paths}}", pdfPaths.stream()
                        .map(path -> "- " + path)
                        .collect(Collectors.joining("\n")),
                "{{work_dir}}", workDir.toString());
        for (var entry : substitutions.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        if (text.contains("{{")) {
            var start = text.indexOf("{{");
            throw new IllegalStateException("Unresolved placeholder in prompt template: "
                    + text.substring(start, Math.min(start + 40, text.length())));
        }
        return text;
    }
}
