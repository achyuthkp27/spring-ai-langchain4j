# Spring AI LangChain4j - AegisAI Project

This repository contains **AegisAI**, a multi-tenant, production-grade GenAI platform designed for banking and fintech operations. The goal of this project is to build an advanced financial operations copilot and agent platform that pushes Spring AI to its absolute limits, with a focus on security, safety, and rigorous guardrails.

## Features

1. **Ops & Compliance Copilot**: Staff can chat with a copilot that answers only from ingested internal documents (policies, KYC docs, statements) with live citations.
2. **Autonomous Agents**: Agents triage disputes triggered by Kafka events, checking policies and drafting resolutions, with human-in-the-loop approval for any money-adjacent actions.
3. **MCP Server**: Internal banking tools are exposed org-wide via an OAuth2-secured Model Context Protocol (MCP) server.
4. **Rigorous Guardrails**: Every LLM call passes through an extensive pipeline for PII redaction, prompt-injection screening, moderation, output validation, and a full audit trail.
5. **Sovereign Mode**: Full local execution capability (Ollama + in-JVM ONNX embeddings + PGVector) ensuring zero data egress for regulated workflows.

## Project Structure

- `aegis-merged/`: **The active backend.** Spring AI + Spring Boot, PostgreSQL/pgvector, Redis-backed
  guardrails, JWT auth, and the full tool/agent surface. This is where fixes and features land.
- `aegis-lc4j/frontend/`: **The active UI.** Next.js app that talks to `aegis-merged`.
- `aegis-ai/`: Superseded by `aegis-merged`. Kept as a study/reference build only — do not fix bugs
  here, they won't reach production.
- `aegis-lc4j/backend/`: Superseded by `aegis-merged`. Also a study/reference build only; the
  `aegis-lc4j/frontend` UI does not talk to this backend.

*Please see [SpringAI-GenAI-Project.md](./SpringAI-GenAI-Project.md) for detailed architecture, capabilities, and learning paths.*
