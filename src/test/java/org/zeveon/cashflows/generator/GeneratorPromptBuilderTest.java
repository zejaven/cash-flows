package org.zeveon.cashflows.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author Stanislav Vafin
 */
class GeneratorPromptBuilderTest {

    @TempDir
    Path dir;

    private final GeneratorPromptBuilder builder = new GeneratorPromptBuilder();

    @Test
    @DisplayName("substitutes every placeholder; pdf paths become a bulleted list")
    void substitutes() throws IOException {
        var template = dir.resolve("prompt.md");
        Files.writeString(template,
                "Bank {{bank_name}}: write parsers/{{script_name}} (id {{parser_id}}), "
                        + "samples:\n{{pdf_paths}}\nscratch in {{work_dir}}");

        var prompt = builder.build(template, "T-Bank", "tbank.py", "tbank-pdf",
                List.of(Path.of("tmp", "statement-1.pdf"), Path.of("tmp", "statement-2.pdf")),
                Path.of(".generator-tmp"));

        assertThat(prompt).isEqualTo("Bank T-Bank: write parsers/tbank.py (id tbank-pdf), "
                + "samples:\n- " + Path.of("tmp", "statement-1.pdf")
                + "\n- " + Path.of("tmp", "statement-2.pdf")
                + "\nscratch in .generator-tmp");
    }

    @Test
    @DisplayName("fails loudly on an unknown placeholder instead of sending it to the agent")
    void unknownPlaceholder() throws IOException {
        var template = dir.resolve("prompt.md");
        Files.writeString(template, "Hello {{bank_name}} and {{surprise}}");

        assertThatThrownBy(() -> builder.build(template, "X", "x.py", "x-pdf",
                List.of(Path.of("a.pdf")), Path.of("tmp")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{{surprise}}");
    }

    @Test
    @DisplayName("fails when the template file is missing")
    void missingTemplate() {
        assertThatThrownBy(() -> builder.build(dir.resolve("nope.md"), "X", "x.py", "x-pdf",
                List.of(Path.of("a.pdf")), Path.of("tmp")))
                .isInstanceOf(IllegalStateException.class);
    }
}
