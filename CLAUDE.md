# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Enterprise RAG (Retrieval-Augmented Generation) knowledge base system. Spring Boot 3.3.5 monolith backend + Vue 3 SPA frontend. Documents are uploaded, parsed, chunked, vectorized in Redis, and retrieved via vector recall + rerank for AI-powered Q&A.

## Build & Run Commands

### Backend (Maven + Java 17)
```bash
mvn clean package -DskipTests    # Package JAR
mvn spring-boot:run              # Run dev server (port 8080)
mvn test                         # Run all tests
mvn test -Dtest=ClassName        # Run single test class
mvn test -Dtest=ClassName#method # Run single test method
```

### Frontend (Node 18+, in `frontend/` directory)
```bash
npm install          # Install deps
npm run dev          # Dev server (port 5173, proxies /auth, /ai, /api to :8080)
npm run build        # Production build to frontend/dist/
```

### Known Pre-existing Test Failures (on master)
3 tests are expected to fail:
- `TextProcessorTest` — Mockito strictness issue
- `EmailServiceTest` — requires live SMTP environment
- `DemoApplicationTests` — context loading (needs all infra services up)

## Architecture

### Layered Structure (base package: `com.example.demo`)
```
Controller/        → Thin REST endpoints, delegates to application services
service/           → Core business logic (25 services)
service/processor/ → Media file processing strategies (PDF, Word, PPT, Excel, image, video)
service/retrieval/ → RAG retrieval strategies (hierarchical → flat fallback)
repository/        → Data access queries (MyBatis-Plus)
mapper/            → MyBatis-Plus mapper interfaces
model/             → Entities, DTOs, enums
Config/            → Spring beans, interceptors, filters, custom memory impl
exception/         → Custom exceptions + GlobalExceptionHandler
```

### Key Design Patterns

**Dual Vector Store** — Two Redis vector store beans (`leafVectorStore` for fine-grained chunks, `summaryVectorStore` for chapter/document summaries) sharing one `JedisPooled` pool. Implements three-tier summary tree: leaf → section → document.

**Strategy Pattern (Retrieval)** — `RetrievalStrategy` interface with `HierarchicalRetrievalStrategy` and `FlatRetrievalStrategy`. Orchestrator `RagRetrievalService` tries hierarchical first, falls back to flat.

**Strategy Pattern (Media Processing)** — `MediaProcessor` interface. `MediaProcessorRegistry` auto-discovers all implementations and routes by MIME type.

**RabbitMQ Producer/Consumer** — `FileProcessProducer`→`FileProcessConsumer` for async doc processing; `FileDeleteProducer`→`FileDeleteConsumer` for async deletion. Both have dead letter queues.

**Three-Layer Chat Memory** — `SummaryWindowChatMemory`: full history in Redis + compressed summaries + sliding window (default 10 messages). Threshold for summarization: 12 messages.

### Auth & Security
- **Sa-Token** for session auth (UUID tokens, 30-day timeout, Redis-backed)
- `@SaCheckPermission` annotations on controller methods (e.g., `ai:chat`, `auth:password:change`)
- `AuthSeedService` seeds default roles/permissions at startup
- `RateLimitInterceptor` — Redis IP-level rate limiting on `/auth/login`, `/auth/register`, `/auth/password/forgot/request`
- `TraceIdFilter` — auto-generates traceId into MDC for request tracing

### API Conventions
- All endpoints return `ApiResponse<T>` (code/message/data/timestamp)
- SSE streaming for AI chat responses (`text/event-stream`)
- Sa-Token header: `satoken` (auto-attached by frontend Axios interceptor)
- Swagger UI at `/swagger-ui.html` (springdoc-openapi)

### Resilience
- Resilience4j circuit breakers on DashScope chat and rerank calls (50% failure threshold, sliding window)
- `@TimeLimiter` on `AiService.chat()` for timeout protection

## Infrastructure Dependencies

All configured in `src/main/resources/application.yaml`:
- **MySQL** — `rag_knowledge` database, 6 tables (schema in `src/main/resources/schema.sql`)
- **Redis** — vector store indexes (`atguigu-index` leaf + summary index), chat memory, rate limiting, Sa-Token sessions
- **RabbitMQ** — async file processing + deletion queues with DLQ
- **MinIO** — object storage, bucket `rag-knowledge`, 500MB upload limit
- **DashScope** — Alibaba Cloud LLM (Tongyi Qianwen) + text-embedding-v3 + gte-rerank-v2

## Database

ORM: MyBatis-Plus 3.5.7 with Flyway migrations. Key tables:
- `rag_unit` — RAG chunks (leaf + summary nodes, hierarchical via `parent_id`/`tree_level`)
- `document_file` — uploaded document records (unique on `user_id, file_hash`)
- `auth_user`, `auth_role`, `auth_permission` + junction tables — RBAC schema

## Conventions

- Lombok everywhere — `@Data`, `@Slf4j`, `@RequiredArgsConstructor`
- All `@Autowired` field injection has been removed; use constructor injection
- `AuthContextService.resolveCurrentUser()` to get the logged-in user in services
- File deduplication via SHA-256 hash (`HashUtils`)
- Document parsing: MinerU (cloud, primary) with Tika (local, fallback)
- Sort parameters use whitelist validation (`createdAt`/`updatedAt` × `ASC`/`DESC`)
