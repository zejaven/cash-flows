package org.zeveon.cashflows.generator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the exact CLI invocations (FR-4.10) — a silently changed flag would
 * surface only minutes into a live agent run.
 *
 * @author Stanislav Vafin
 */
class AgentCliRunnerCommandTest {

    @Test
    @DisplayName("claude command: streaming JSON, bypassPermissions, optional model")
    void claudeCommand() {
        assertThat(AgentCliRunner.claudeCommand("claude.cmd", "claude-fable-5")).containsExactly(
                "claude.cmd", "-p",
                "--output-format", "stream-json", "--verbose", "--include-partial-messages",
                "--permission-mode", "bypassPermissions",
                "--model", "claude-fable-5");

        assertThat(AgentCliRunner.claudeCommand("claude", null)).containsExactly(
                "claude", "-p",
                "--output-format", "stream-json", "--verbose", "--include-partial-messages",
                "--permission-mode", "bypassPermissions");

        assertThat(AgentCliRunner.claudeCommand("claude", "")).doesNotContain("--model");
    }

    @Test
    @DisplayName("codex command: exec json, workspace-write sandbox, stdin prompt")
    void codexCommand() {
        var repoRoot = Path.of("C:\\repo");
        assertThat(AgentCliRunner.codexCommand("codex.cmd", "gpt-5.5-codex", repoRoot)).containsExactly(
                "codex.cmd", "-a", "never", "exec",
                "--json", "--ephemeral", "--sandbox", "workspace-write",
                "--model", "gpt-5.5-codex",
                "-C", repoRoot.toString(), "-");

        assertThat(AgentCliRunner.codexCommand("codex", "", repoRoot)).containsExactly(
                "codex", "-a", "never", "exec",
                "--json", "--ephemeral", "--sandbox", "workspace-write",
                "-C", repoRoot.toString(), "-");
    }
}
