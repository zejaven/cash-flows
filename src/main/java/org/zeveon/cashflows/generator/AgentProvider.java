package org.zeveon.cashflows.generator;

/**
 * Coding-agent CLI used for parser generation (FR-4.10). Only local CLIs are
 * supported — no direct API integrations by design (the CLIs carry their own
 * auth and agentic loop).
 *
 * @author Stanislav Vafin
 */
public enum AgentProvider {
    CLAUDE,
    CODEX
}
