# TODO

Roadmap of remaining work to take Receipt Splitter from scaffolding to a
working app. Full rationale for each piece lives in
[ARCHITECTURE.md](ARCHITECTURE.md); this file just tracks what's left.

Per this repo's workflow (see [CLAUDE.md](CLAUDE.md)), each numbered section
below is meant to go through `/brainstorm` → `/write-plan` → `/execute-plan`
→ `/code-review` rather than being coded ad hoc.

## 1. Open architectural decisions (blocking)

- [ ] Room-code generation scheme (length, charset, collision handling)
      — format settled (6 chars; digits 2-9 and A-Z minus I/L/O); the
      generator and its collision retry are still to build
- [ ] Bill expiry cleanup mechanism (scheduled job vs. lazy delete-on-read)
- [ ] Postgres hosting for deploy (Railway vs. Render)

## 2. Backend domain model

- [x] `Bill` — `roomCode`, `payerName`, `subtotalCents`, `taxCents`,
      `tipCents`, `totalCents`, `status` (DRAFT/OPEN/CLOSED), `createdAt`,
      `expiresAt`; aggregate root owning items and participants
- [x] `Item` — `bill`, `name`, `priceCents` (line total), `quantity`
      (number of claimable units)
- [x] `Participant` — `bill`, `name`, `sessionToken`, `joinedAt`
- [x] `ItemClaim` — join table (`item`, `participant`, `unitIndex`)
- [x] JPA repositories for each
- [x] Flyway `V1` migration; `ddl-auto` switched to `validate`
- [x] `ScaffoldStubsTests` converted to `DomainMappingTests`; entity-level
      persistence tests added

## 3. `ReceiptParser` (core OCR logic)

Build and test this in isolation first — it's pure text-fragment-in,
structured-data-out logic with no Vision API or HTTP involved, and it's
called out in the spec as the highest-value, most-ownable piece of code in
the project. Proving it out before wiring a controller around it de-risks
the hardest part early instead of leaving it for last.

- [ ] `ReceiptParser` — group text fragments into rows by y-coordinate,
      regex-match trailing price per row, route subtotal/tax/tip/total/change
      keyword rows into `Bill` fields, discard unpriced rows
      (`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/receipt/ReceiptParser.java`)
- [ ] JUnit fixtures: capture real Vision API output from a handful of
      receipt formats, test `ReceiptParser` against them test-first, before
      the controller below exists

## 4. Receipt upload & Vision integration

- [ ] Google Cloud Vision API project/service account setup, key kept
      server-side only
- [ ] `ReceiptController` — `POST /api/bills` multipart upload → Vision call
      → parser → return editable draft (not yet persisted)
      (`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/receipt/ReceiptController.java`)

## 5. Bill/claims REST API

`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/BillController.java`

- [ ] Confirm draft → persist bill + items, generate room code, status →
      `OPEN`
- [ ] `GET /api/bills/{roomCode}` — full state fetch (also used for
      reconnect resync)
- [ ] Join-room endpoint — create `Participant` + `sessionToken`
- [ ] Settle-up calculation (computed on read, not stored): per-item price ÷
      claimers, tax/tip distributed proportional to subtotal share, cent
      rounding to payer, unclaimed items surfaced explicitly — independent
      pure logic, build and unit-test before wiring the claim endpoint below
- [ ] `POST /api/bills/{roomCode}/claims` — persist `ItemClaim`, then
      trigger broadcast
- [ ] `@SpringBootTest` coverage for claim/calculation logic

## 6. Realtime sync

- [ ] `BillWebSocketConfig` — STOMP broker, topic `/topic/bills/{roomCode}`
      (`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/websocket/BillWebSocketConfig.java`)
- [ ] Broadcast claim diffs from the claim endpoint to that topic (write
      path stays REST-only, never over the socket)

## 7. Frontend

- [ ] `UploadReceipt` — photo capture/upload, call `POST /api/bills`
      (`frontend/src/routes/UploadReceipt.tsx`)
- [ ] `ReviewItems` — editable OCR draft (merge/delete/correct rows) before
      confirm (`frontend/src/routes/ReviewItems.tsx`)
- [ ] `BillRoom` — join-by-name, own the WebSocket connection, live claim
      UI, share link, full-resync-then-resubscribe on reconnect
      (`frontend/src/routes/BillRoom.tsx`)
- [ ] `Summary` — per-participant "owes $X to payer" view
      (`frontend/src/routes/Summary.tsx`)
- [ ] Wire up React Query for REST calls
- [ ] Add SockJS/STOMP client + a small store (Zustand or Context) for live
      claim state
- [ ] Vitest + Testing Library component tests for the claiming interaction
- [ ] Replace/extend the placeholder `App.test.tsx`

## 8. Deployment

CI is in place (see
[the CI/CD spec](docs/superpowers/specs/2026-08-19-ci-cd-pipeline-design.md)):
every PR runs both test suites with coverage plus CodeQL, and every merge to
`main` publishes `ghcr.io/jacksonfalgoust/receiptparser-backend`. What
remains is pointing a host at that image.

- [ ] Backend → Railway or Render (per decision in section 1), pulling the
      published GHCR image rather than building from source
- [ ] Postgres instance on the same host, with `SPRING_DATASOURCE_URL`,
      username, and password injected as environment variables
- [ ] Frontend → Vercel or Netlify, with `VITE_API_BASE_URL` pointed at the
      deployed backend
- [ ] Vision API key and DB creds as server-side env vars/secrets, never
      shipped to the client
- [ ] Add the deploy job to `ci.yml`, gated on the `docker` job succeeding

## 9. Optional polish

- [ ] One Playwright happy-path smoke test end-to-end

## 10. CI/CD follow-ups

Deliberately deferred when the pipeline was built. Roughly in the order
they become worth doing.

**Blocked on real tests existing (sections 2–7):**

- [ ] Coverage thresholds — add `jacoco:check` rules and Vitest
      `coverage.thresholds` once `ReceiptParser` has its Vision fixtures.
      A threshold against today's stub code would be arbitrary; one set to
      0% is theater.
- [ ] Publish coverage somewhere visible — Codecov, or an HTML report
      pushed to GitHub Pages. Adds an external dependency and a token, so
      it only pays for itself once the number means something.
- [ ] Playwright happy-path smoke test in CI (see section 9) — needs the
      full stack running in the workflow, so it lands after deployment.

**Blocked on deployment (section 8):**

- [ ] Deploy job consuming the GHCR image, gated on `docker` succeeding.
- [ ] Post-deploy smoke check against the live URL, so a green pipeline
      means "it is actually serving", not just "it built".
- [ ] Staging environment and a manual promotion gate to production.

**Not blocked — can be done any time:**

- [ ] Dependabot (`.github/dependabot.yml`) for three ecosystems: `maven`
      in `/backend`, `npm` in `/frontend`, and `github-actions` in `/`.
      The third matters most — it is what stops the pinned action versions
      from silently going stale.
- [ ] Branch protection on `main`: require the `Backend tests`,
      `Frontend checks`, and both `Analyze` checks to pass before merge.
      This is a repo setting, not a file, so it cannot be committed.
- [ ] Testcontainers for the backend suite, replacing the Postgres service
      container and removing the "`docker compose up -d` must be running"
      precondition for local `mvn test` too. Genuinely better than the
      current setup; kept out of the CI change because it is application
      config, not pipeline config.
- [ ] Pin actions by commit SHA instead of major tag, for supply-chain
      hardening. Dependabot can keep the SHAs current.
- [ ] Container image scanning (Trivy or Grype) on the built image, and
      SBOM generation via `docker/build-push-action`'s `sbom: true`.
      CodeQL covers source; neither covers the base image's OS packages.
- [ ] Multi-arch image (`linux/amd64,linux/arm64`) via QEMU + Buildx —
      only needed if the chosen host runs ARM.
- [ ] Release tagging: semver tags on `main` producing versioned image
      tags alongside `sha-` and `latest`.
- [ ] Enable secret scanning and push protection in repo settings.
