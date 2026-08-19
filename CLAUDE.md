# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

The project is scaffolded: a Spring Boot backend (`backend/`, Maven), a Vite
React+TypeScript frontend (`frontend/`), and a `docker-compose.yml` for local
Postgres all exist and build/boot. There is no domain logic yet — backend
entities/controllers and frontend routes are empty stubs (see
`docs/superpowers/plans/2026-08-18-project-scaffolding.md` for what the
scaffolding pass covered).

Local setup (see root `README.md` for details):

```bash
docker compose up -d          # Postgres, from repo root
cd backend && mvn test        # or: mvn spring-boot:run
cd frontend && npm install && npm run dev
```

Backend tests are `@SpringBootTest` against the real Postgres container
above — `docker compose up -d` must be running before `mvn test` will pass.

As real feature work lands, update this section (and add lint/single-test
commands) to stay accurate — don't let it drift back into describing a
past state of the repo.

## What this project is

Receipt Splitter — a portfolio piece: photograph a restaurant/grocery
receipt, OCR it into structured line items, let each participant in a group
claim what they had on a shared live-updating page, and compute what
everyone owes the payer. Full spec, data model, and rationale (including
rejected alternatives) live in
[ARCHITECTURE.md](ARCHITECTURE.md) —
read it before planning implementation work; the summary below is not a
substitute for the open items and tradeoffs recorded there.

## Planned architecture

```
React SPA (Vite + TypeScript)
     │  REST (create bill, upload receipt, join room, claim items)
     │  WebSocket/STOMP (live item-claim broadcasts)
     ▼
Spring Boot backend
     ├─ ReceiptController   → Google Cloud Vision API → ReceiptParser
     ├─ BillController      → Postgres (bills, items, participants, claims)
     └─ BillWebSocketConfig → STOMP broker, topic: /topic/bills/{roomCode}
     ▼
Postgres
```

- **No auth** — participants join via a shareable room-code link and a typed
  display name; identity across reconnects is a client-stored
  `sessionToken`, not a login.
- **`ReceiptParser`** (backend) is the highest-value, most-ownable piece of
  code in the project: groups Vision API text fragments into rows by
  y-coordinate, regex-matches a trailing price per row to split
  name/price, and routes subtotal/tax/tip/total keyword rows into the
  `Bill` fields instead of treating them as items. OCR output is returned
  to the client as an **editable draft** before persisting — the review
  step is load-bearing, not optional polish.
- **Realtime sync**: claims are written via REST
  (`POST /api/bills/{roomCode}/claims`), then broadcast as a diff over
  STOMP to `/topic/bills/{roomCode}`. Writes never go over the socket
  directly. On reconnect, the client does one full `GET` resync before
  resuming the subscription.
- **Settle-up is computed on read, never stored**: per-item price is split
  evenly across its claimers; tax/tip are distributed proportionally to
  each participant's subtotal share (not evenly); rounding leftovers go to
  the payer; unclaimed items are surfaced explicitly rather than
  redistributed.
- **`ItemClaim`** is a single join table modeling both shared and
  exclusively-claimed items — no special-casing between the two.

See the spec's "Open Items for Implementation Planning" section for
unresolved decisions (room-code scheme, bill-expiry cleanup mechanism,
Postgres hosting) before making unilateral calls on those.

## Tooling

- **Library/framework/API docs** (Spring Boot, Spring WebSocket/STOMP,
  React, Vite, React Query, Google Cloud Vision client libraries, etc.):
  use the `find-docs` skill (context7) rather than relying on training
  data — it fetches current, version-accurate documentation and code
  examples. Prefer it over web search for anything library-specific:
  API syntax, config, version migrations, setup steps.
- **Browser automation / manual verification of the frontend**: use the
  `playwright-cli` skill to drive the app in a real browser (navigate,
  interact, screenshot) once a frontend exists to point it at — useful for
  confirming a change works end-to-end, not just that unit tests pass.

## Git & Workflow Strategy
- **Branch naming:** `feature/` or `bugfix/` followed by name of feature/fix (e.g, `feature/login`).
- **Commits:** Do not commit code or document changes to git without asking first. Never co-author commits (no `Co-Authored-By` trailer).
- **Skills:** Disable automatic git commits during TDD execution. Do not use git worktress - ignore `superpowers:using-git-worktrees`. When grilling, ask one question at a time.
- **Workflow:** (superpowers), two phases:
  - **Plan phase:** `/brainstorm` to explore and shape the idea, using the `/grill-with-docs` skill during brainstorming to stress-test the design and produce ADRs/glossary as we go, then `/write-plan` to turn it into a written plan.
  - **Execution phase:** Always create a new branch before implementing a plan, then `/execute-plan` to implement the plan with subagents, then `/code-review` to review the code after implementation. Never co-author commits.
- **Deployment:** Open a PR against `main` using the `gh` CLI (`gh pr create`). Do not push directly to `main`. Always delete the branch after the PR is merged.