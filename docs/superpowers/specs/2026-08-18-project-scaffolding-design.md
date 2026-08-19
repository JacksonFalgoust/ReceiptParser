# Project Scaffolding — Design Spec

**Date:** 2026-08-18
**Status:** Approved for planning

## Purpose

Stand up runnable, empty-but-wired skeletons for both halves of Receipt
Splitter — the Spring Boot backend and the React frontend — so implementation
work (per [ARCHITECTURE.md](../../../ARCHITECTURE.md)) has a project to build
inside instead of starting from nothing. This spec
covers scaffolding decisions only: no domain logic, no OCR/Vision
integration, no real endpoints.

## Repo layout

Monorepo — both projects live as subfolders of this repo:

```
ReceiptParser/
├─ backend/            Maven project (Spring Boot)
├─ frontend/            Vite project (React + TypeScript)
├─ docker-compose.yml   local Postgres only
└─ docs/...             existing spec + planning docs
```

## Versions & tooling

- **Backend:** Spring Boot 4.0.x (latest stable GA line — not the 4.1 RC),
  Java 21 (current LTS; Boot 4.0's floor is Java 17, but 21 is the safer
  target for tooling/library support and job-posting familiarity), Maven.
- **Frontend:** Vite (current stable major via `npm create vite@latest`) +
  React + TypeScript template, npm — resolved to Vite 8 at scaffold time.
- **Postgres:** Docker Compose running `postgres:16`, local dev only.
  Production hosting (Railway vs. Render) stays an open item per the main
  spec, decided at deploy time.

## Backend scaffold

- `pom.xml` dependencies: Spring Web, Spring WebSocket, Spring Data JPA,
  PostgreSQL driver, Validation, Spring Boot DevTools.
- `groupId`: `com.jacksonfalgoust`, `artifactId`: `receipt-splitter`.
- Package structure mirrors the components named in the main spec's
  architecture diagram, as empty stub classes (no logic):
  - `com.jacksonfalgoust.receiptsplitter.bill` — `BillController`, `Bill`
    entity
  - `com.jacksonfalgoust.receiptsplitter.item` — `Item` entity
  - `com.jacksonfalgoust.receiptsplitter.participant` — `Participant` entity
  - `com.jacksonfalgoust.receiptsplitter.claim` — `ItemClaim` entity
  - `com.jacksonfalgoust.receiptsplitter.receipt` — `ReceiptController`,
    `ReceiptParser` (empty class — this is the piece that gets real logic
    first once implementation starts)
  - `com.jacksonfalgoust.receiptsplitter.websocket` — `BillWebSocketConfig`
  - `com.jacksonfalgoust.receiptsplitter.config` — general `@Configuration`
    beans (e.g. CORS for the Vite dev server)
- `application.yml` pointed at the Docker Compose Postgres instance
  (`localhost:5432`), with a `test` profile placeholder for later.
- One JUnit context-loads test so `mvn test` proves the harness works.

## Frontend scaffold

- Base: `npm create vite@latest` React + TypeScript template.
- Added dependencies: `@tanstack/react-query`, `react-router`, `zustand`,
  `@stomp/stompjs`, `sockjs-client` (+ `@types/sockjs-client`) — matching the
  main spec's "Frontend Structure" section.
- Route stubs for the four screens named in the main spec, wired into a
  router but with placeholder content: `UploadReceipt`, `ReviewItems`,
  `BillRoom`, `Summary`.
- Vitest + Testing Library configured, with one smoke test rendering the
  app shell.
- `.env.example` holding the backend API base URL.

## Out of scope for this pass

Left for later implementation plans, per the main spec's "Open Items for
Implementation Planning":

- Room-code generation scheme (length, character set, collisions).
- Bill-expiry cleanup mechanism (scheduled job vs. lazy delete-on-read).
- Google Cloud Vision API integration and key management.
- Any real domain/business logic, REST handlers, or WebSocket message
  handling.
- Production Postgres hosting choice and deployment config
  (Railway/Render/Vercel/Netlify).

## Testing

- Backend: the one context-loads test described above — enough to confirm
  the app boots with its dependencies wired to a real (Dockerized) Postgres.
- Frontend: the one smoke test described above.
- No feature tests yet — there's no feature, only scaffolding.
