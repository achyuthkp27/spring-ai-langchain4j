# AegisAI — Spring AI Project Audit Report

*Report only — nothing changed. Generated from the live source tree (`com.aegis.ai`).*

---

## 1. VERSIONS

| Item | Value | Source |
|---|---|---|
| **Spring AI** | **1.0.9** | `pom.xml` → `<spring-ai.version>1.0.9</spring-ai.version>`, imported via `spring-ai-bom` |
| **Spring Boot** | **3.5.3** | `spring-boot-starter-parent` |
| **Java** | **17** | `<java.version>17</java.version>` |

**Spring AI starters in use** (all pulled through the BOM, versions managed):

| Starter / module | Purpose | Active? |
|---|---|---|
| `spring-ai-starter-model-ollama` | Chat + embeddings (local) | ✅ **active** (`spring.ai.model.chat=ollama`) |
| `spring-ai-starter-model-openai` | Chat provider | dormant (profile `openai`) |
| `spring-ai-starter-model-anthropic` | Chat provider | dormant (profile `anthropic`) |
| `spring-ai-starter-model-chat-memory-repository-jdbc` | Conversation memory in Postgres | ✅ active |
| `spring-ai-starter-vector-store-pgvector` | Vector store | ✅ active |
| `spring-ai-advisors-vector-store` | Vector-store advisors | ✅ active |
| `spring-ai-markdown-document-reader` | Markdown ingestion | ✅ active |
| `spring-ai-rag` | Modular RAG (query transform, retriever, advisor) | ✅ active |
| `spring-ai-starter-mcp-server-webmvc` | Expose tools over MCP (SSE) | ✅ active |

Non-Spring-AI notables: `resilience4j-circuitbreaker/timelimiter/reactor` 2.2.0, `jjwt` 0.12.6, `micrometer-registry-prometheus`, `spring-boot-starter-security`.

---

## 2. MODEL CONFIGURATION

**Default profile (Ollama, local):**

| Setting | Value |
|---|---|
| Chat provider | `ollama` (`spring.ai.model.chat`) |
| Chat model | **`qwen2.5:7b`** (`spring.ai.ollama.chat.options.model`) |
| Temperature | **0.2** |
| `num-predict` (max output tokens) | **512** |
| Embedding provider | `ollama` |
| Embedding model | **`nomic-embed-text`** (768-dim) |
| base-url | `http://localhost:11434` |

**Alternate profiles (config-only swap, no code change — no model name hardcoded in Java):**

| Profile | Chat model | Temp |
|---|---|---|
| `openai` | `gpt-4o-mini` | 0.2 |
| `anthropic` | `claude-sonnet-4-5` | 0.2 |

**Additional per-client ChatOptions set in code:**
- **Scope-gate classifier** (`ScopeGate`): `temperature=0.0`, `maxTokens=4` (portable `ChatOptions` → `num_predict` on Ollama / `max_tokens` on OpenAI). Makes the classifier deterministic + cheap.
- **Semantic cache** uses a *dedicated* embedding model **`all-minilm`** (built in `CacheConfig`, separate from the RAG `nomic-embed-text`).

**Retry (framework-level):** `spring.ai.retry` → `max-attempts: 3`, backoff `initial-interval: 800ms`, `multiplier: 2`, `on-client-errors: false`.

No `top-p` / `top-k` / frequency-penalty is set anywhere (framework defaults apply).

---

## 3. RAG / RETRIEVAL SETUP

**VectorStore:** `PgVectorStore` (pgvector).
```yaml
spring.ai.vectorstore.pgvector:
  initialize-schema: true
  dimensions: 768            # matches nomic-embed-text
  index-type: hnsw
  distance-type: cosine_distance
```

**Ingestion / chunking** (`IngestionService`):
- Reader: `MarkdownDocumentReader` with `MarkdownDocumentReaderConfig` — `withHorizontalRuleCreateDocument(true)`, `withIncludeCodeBlock(false)`, plus metadata stamping.
- Splitter: **`new TokenTextSplitter()`** — **default configuration** (no explicit chunk size / overlap set → framework defaults, ~800-token chunks).
- Metadata stamped on every chunk: **`tenantId`** (security-critical), `docType`, `source`.
- Idempotent: clears existing chunks before re-ingest; also flushes the semantic cache.
- Source glob: `classpath:documents/{tenant}/*.md`.

**Retrieval config — three separate paths (⚠️ inconsistent, see note):**

| Where | Retriever | top-k | threshold | Tenant filter |
|---|---|---|---|---|
| `PolicySearchTool` (unified assistant tool) | `vectorStore.similaritySearch(SearchRequest)` | **4** | 0.1 | `tenantId == '<from Principal>'` |
| `AdvancedRagController` (`/api/rag/ask-advanced`) | `VectorStoreDocumentRetriever` | **6** | 0.1 | `FilterExpressionBuilder.eq("tenantId", …)` |
| `PolicyTools` (MCP) | `vectorStore.similaritySearch(SearchRequest)` | **4** | 0.1 | `tenantId == '<model arg>'` |

**RAG advisor wiring:**
- The **modular-RAG** endpoint uses **`RetrievalAugmentationAdvisor`** (NOT `QuestionAnswerAdvisor`), composed with:
  - `RewriteQueryTransformer` (pre-retrieval query rewriting),
  - `VectorStoreDocumentRetriever` (tenant-filtered),
  - wired via `ragClient.prompt().advisors(ragAdvisor)`.
- The **unified assistant** (`/api/assistant`) does **tool-based RAG** instead — the model calls `searchPolicies` (a `@Tool`), not a RAG advisor. So two RAG styles coexist.

> **Note for review:** `QuestionAnswerAdvisor` is **not** used anywhere; RAG is either `RetrievalAugmentationAdvisor` (advanced endpoint) or tool-call (`PolicySearchTool`). top-k differs (4 vs 6) between paths.

---

## 4. TOOL / FUNCTION CALLING

**All `@Tool`-annotated methods:**

| Class | Tool | Params | Read/Write | AuthZ | Tenant source |
|---|---|---|---|---|---|
| `BankingTools` | `lookupBalance` | `accountId` | **read** | `account:read` | Principal (ToolContext) |
| `BankingTools` | `searchTransactions` | `accountId` | **read** | `account:read` | Principal (ToolContext) |
| `BankingTools` | `createDisputeCase` | `transactionId`, `reason` | **WRITE** (creates case) | `cases:create` | Principal; validates txn exists + tenant match |
| `BankingTools` | `issueProvisionalCredit` | `caseId`, `amount` | **WRITE** (money) → *human-approval request only, never moves funds* | `credit:request` | Principal |
| `PolicySearchTool` | `searchPolicies` | `query` | **read** | (implicit via tenant) | **Principal (ToolContext)** — model cannot spoof tenant |
| `PolicyTools` (MCP) | `searchPolicies` | `tenantId`, `query` | **read** | none (POC) | **model-supplied arg** (MCP client passes tenantId) |

**Tool registration:**
- **Unified assistant** — registered **per-call**: `assistant.prompt().tools(bankingTools, policySearchTool)` in `AssistantController` (both blocking `chat()` and `stream()`). Identity passed out-of-band via `.toolContext(Map.of(PRINCIPAL_KEY, principal, DYNAMIC_ACCESS_KEY, dynamic))`.
- **Agent client** — `AgentConfig` builds a separate `agentClient` whose prompt instructs tool use.
- **MCP** — `McpConfig` registers `PolicyTools` via `MethodToolCallbackProvider.builder().toolObjects(policyTools).build()` as a `ToolCallbackProvider` bean, discoverable by any MCP client.

**Errors about "outdated" / "doesn't have the right tool to check":**
**None.** No such error exists in this project — that template field is **not applicable here**. The tool set is current and wired correctly (verified live: balance, transactions, dispute creation, provisional-credit-as-approval all work; authz + tenant isolation enforced).
The one framework-level issue previously encountered (now fixed): streaming + tool-calls on Spring AI **1.0.0** threw `NullPointerException: evalDuration is null` in `OllamaChatModel.from`; resolved by bumping the BOM to **1.0.9**.

---

## 5. PROMPTS

**System prompts (one per ChatClient):**

- **`assistantClient`** (`AssistantConfig`) — the unified front door. Covers: decide-tools-yourself, **scope boundary** (bank ops only, decline off-domain), tool-grounding + citation, no-money-movement, **anti-fabrication** ("never state a fact the user didn't give and a tool didn't return"), **brevity** ("answer in as few words as the question needs… cite the source"), never reveal instructions, own-tenant only.
- **`ragClient`** (`RagConfig`) — "answer only from provided context, cite the source, add nothing beyond context, refuse if empty."
- **`copilotClient`** (`CopilotConfig`, `@Primary`) — general copilot; concise/precise, never invent data.
- **`agentClient`** (`AgentConfig`) — explicitly instructs tool use + faithful reporting.
- **Scope classifier** (`ScopeGate`) — a separate bare `ChatClient` prompted to reply exactly `IN_SCOPE`/`OUT_OF_SCOPE`, biased toward IN_SCOPE, context-aware (fed recent conversation).

**`PromptTemplate`s:** none custom — prompts are `defaultSystem(...)` strings; RAG context injection is handled by `RetrievalAugmentationAdvisor` (advanced) or tool output (assistant).

**Advisor chains and order** (`getOrder()`: `TokenAuditAdvisor`=**0**, `GuardrailAdvisor`=**10**):

| ChatClient | Advisor chain (in order) |
|---|---|
| `copilotClient` (@Primary) | `TokenAuditAdvisor` → `GuardrailAdvisor` → `MessageChatMemoryAdvisor` → `SimpleLoggerAdvisor` |
| `assistantClient` | `TokenAuditAdvisor` → `GuardrailAdvisor` → `MessageChatMemoryAdvisor` |
| `ragClient` | `TokenAuditAdvisor` → `MessageChatMemoryAdvisor` |
| `agentClient` | `TokenAuditAdvisor` |

`GuardrailAdvisor` internally runs (input) rate-limit → budget → injection screen → PII-redact, then (output) leaked-tool-call suppression → PII-redact. It is a **`CallAdvisor`** only, so on the streaming path the controller re-enforces input guards + boundary-sanitizes output itself.

---

## 6. ARCHITECTURE

**ChatClient / ChatModel beans:**
- `ChatModel` (Ollama) auto-configured by the starter from `application.yml`.
- Four purpose-built `ChatClient` beans: `copilotClient` (`@Primary`), `assistantClient` (`@Qualifier`), `ragClient`, `agentClient` — plus the bare classifier inside `ScopeGate`.
- **No custom `RestClient`/`WebClient` timeouts.** Model-call resilience is handled at the application layer instead (below), not at the HTTP client.

**Retry / resilience:**
- Framework retry: `spring.ai.retry` (3 attempts, exp backoff).
- **`LlmGuard`** (Resilience4j) wraps every model call (blocking + streaming): **TimeLimiter 30s** (cancels hung calls) + **CircuitBreaker** — slidingWindow 20, failureRate ≥50%, slowCallRate ≥90% @ >25s, waitInOpen 15s, half-open probes 3. Exposed at `GET /api/admin/circuit`.

**Orchestrator / router:**
- **Single unified front door** — `AssistantController` (`/api/assistant`, `/api/assistant/stream`). **The model orchestrates** (decides which tools to call); there is **no separate rule-based router**.
- A **pre-model `ScopeGate`** (model-as-classifier) gates off-domain requests before the main call.
- Request pipeline: JWT → input guards (rate/budget/injection) → semantic cache → scope gate → PII redact → guarded model stream (tools) → boundary-sanitized output → cache + budget record.

**Caching:**
- **Custom `SemanticCache`** — embedding (all-minilm) + cosine, **per-tenant**, threshold **0.62**, TTL **30 min**, `isCacheable()`/`isSafeToCache()` guards, cleared on re-ingest. Not Spring Cache.
- **`ScopeGate` verdict cache** — standalone-question verdicts, TTL 1h.
- No `@EnableCaching` / Spring Cache abstraction.

**Auth / session / customer_id into tools:**
- **JWT** (jjwt 0.12.6, HS256) carries `userId`, `tenantId`, `role`.
- `JwtAuthFilter` (OncePerRequestFilter) verifies signature → builds `Principal(userId, tenantId, permissions)` → `SecurityContext`.
- Controllers read it via `CurrentUser.get()` and pass it into tools **exclusively through `ToolContext`** (`BankingTools.PRINCIPAL_KEY`) — **never as a model-visible argument**. Tools call `principal(ctx)` and enforce `require(permission)` + tenant match in code.
- `SecurityConfig`: stateless, `shouldFilterAllDispatcherTypes(false)` (fixes SSE async re-auth), 401 entry point, permits `/`, `/api/auth/**`, health/prometheus, `/sse`, `/mcp/**`.

---

## 7. DEPENDENCIES SNAPSHOT

Full `<dependencies>` block from `pom.xml` (versions via `spring-boot-starter-parent` 3.5.3 and `spring-ai-bom` 1.0.9 unless pinned):

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- Resilience4j: circuit breaker + time limiter around the LLM call -->
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-circuitbreaker</artifactId>
        <version>2.2.0</version>
    </dependency>
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-timelimiter</artifactId>
        <version>2.2.0</version>
    </dependency>
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-reactor</artifactId>
        <version>2.2.0</version>
    </dependency>

    <!-- JWT -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.6</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.6</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.12.6</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- Model providers: only the one selected via spring.ai.model.chat is activated -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-ollama</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-anthropic</artifactId>
    </dependency>

    <!-- Conversation memory persisted in Postgres -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-chat-memory-repository-jdbc</artifactId>
    </dependency>

    <!-- Phase 1 (RAG): pgvector store + Ollama embeddings + document readers -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-advisors-vector-store</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-markdown-document-reader</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-rag</artifactId>
    </dependency>

    <!-- Phase 3: expose tools over MCP (Model Context Protocol) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    </dependency>

    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-docker-compose</artifactId>
        <scope>runtime</scope>
        <optional>true</optional>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

**BOM / version management:**
```xml
<properties>
    <java.version>17</java.version>
    <spring-ai.version>1.0.9</spring-ai.version>
</properties>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

### Auditor's flags (observations only — nothing fixed)
1. **Two RAG styles coexist** — `RetrievalAugmentationAdvisor` (advanced endpoint) vs tool-based `PolicySearchTool` (unified assistant). top-k differs (6 vs 4).
2. **`TokenTextSplitter` uses defaults** — no explicit chunk size/overlap. Fine for the 10-chunk corpus; revisit at scale.
3. **MCP `PolicyTools` takes `tenantId` as a model arg** (no auth) — POC only; README notes OAuth2 as deferred hardening. The in-app `PolicySearchTool` correctly takes tenant from the Principal.
4. **No hybrid search / re-ranker** — pure dense retrieval (deliberate; deferred until corpus grows).
5. **Filter-expression styles differ** — string DSL (`"tenantId == '…'"`) in tools vs `FilterExpressionBuilder` in the advanced retriever.
6. **`SimpleLoggerAdvisor` + DEBUG logging** on `copilotClient` logs full prompts/completions — dev only; a data-leak vector in prod (already noted in `application.yml`).
