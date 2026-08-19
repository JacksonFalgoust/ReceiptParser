# TODO

Roadmap of remaining work to take Receipt Splitter from scaffolding to a
working app. Full rationale for each piece lives in
[ARCHITECTURE.md](ARCHITECTURE.md); this file just tracks what's left.

Per this repo's workflow (see [CLAUDE.md](CLAUDE.md)), each numbered section
below is meant to go through `/brainstorm` → `/write-plan` → `/execute-plan`
→ `/code-review` rather than being coded ad hoc.

## 1. Open architectural decisions (blocking)

- [ ] Room-code generation scheme (length, charset, collision handling)
- [ ] Bill expiry cleanup mechanism (scheduled job vs. lazy delete-on-read)
- [ ] Postgres hosting for deploy (Railway vs. Render)

## 2. Backend domain model

- [ ] `Bill` — add `roomCode`, `payerName`, `subtotal`, `tax`, `tip`,
      `total`, `status` (DRAFT/OPEN/CLOSED), `createdAt`, `expiresAt` +
      accessors
      (`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/Bill.java`)
- [ ] `Item` — `billId`, `name`, `price`, `quantity`
      (`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/item/Item.java`)
- [ ] `Participant` — `billId`, `name`, `sessionToken`
      (`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/participant/Participant.java`)
- [ ] `ItemClaim` — join table (`itemId`, `participantId`)
      (`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/claim/ItemClaim.java`)
- [ ] JPA repositories for each
- [ ] Update `ScaffoldStubsTests` per its own comment once fields land, and
      add real entity-level tests

## 3. OCR pipeline

- [ ] Google Cloud Vision API project/service account setup, key kept
      server-side only
- [ ] `ReceiptParser` — the core owned logic: group text fragments into rows
      by y-coordinate, regex-match trailing price per row, route
      subtotal/tax/tip/total/change keyword rows into `Bill` fields, discard
      unpriced rows
      (`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/receipt/ReceiptParser.java`)
- [ ] `ReceiptController` — `POST /api/bills` multipart upload → Vision call
      → parser → return editable draft (not yet persisted)
      (`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/receipt/ReceiptController.java`)
- [ ] JUnit fixtures: capture real Vision API output from a handful of
      receipt formats, test `ReceiptParser` against them (highest-value test
      target in the project)

## 4. Bill/claims REST API

`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/BillController.java`

- [ ] Confirm draft → persist bill + items, generate room code, status →
      `OPEN`
- [ ] `GET /api/bills/{roomCode}` — full state fetch (also used for
      reconnect resync)
- [ ] Join-room endpoint — create `Participant` + `sessionToken`
- [ ] `POST /api/bills/{roomCode}/claims` — persist `ItemClaim`, then
      trigger broadcast
- [ ] Settle-up calculation (computed on read, not stored): per-item price ÷
      claimers, tax/tip distributed proportional to subtotal share, cent
      rounding to payer, unclaimed items surfaced explicitly
- [ ] `@SpringBootTest` coverage for claim/calculation logic

## 5. Realtime sync

- [ ] `BillWebSocketConfig` — STOMP broker, topic `/topic/bills/{roomCode}`
      (`backend/src/main/java/com/jacksonfalgoust/receiptsplitter/websocket/BillWebSocketConfig.java`)
- [ ] Broadcast claim diffs from the claim endpoint to that topic (write
      path stays REST-only, never over the socket)

## 6. Frontend

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

## 7. Deployment

- [ ] Backend + Postgres → Railway or Render (per decision in section 1)
- [ ] Frontend → Vercel/Netlify
- [ ] Vision API key and DB creds as server-side env vars/secrets, never
      shipped to the client

## 8. Optional polish

- [ ] One Playwright happy-path smoke test end-to-end
