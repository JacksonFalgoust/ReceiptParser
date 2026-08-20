# Receipt Splitter — Design Spec

**Date:** 2026-08-18
**Status:** Approved for planning

## Purpose

A portfolio project demonstrating full-stack engineering with a Spring Boot
backend and React frontend: photograph a restaurant/grocery receipt, extract
line items via OCR, let each person in a group claim what they had on a
shared live-updating page, and compute what everyone owes the payer.

Primary goal is a polished, finishable, demoable resume piece — not a
production app for ongoing real-world use. Scope favors depth on the two
technically interesting pieces (OCR-to-structured-data parsing, realtime
multi-client sync) over breadth of features.

## Non-goals

- No user accounts / authentication. Participants join a bill via a shareable
  room-code link and type a display name.
- No ongoing group balances across multiple bills, no debt-simplification
  graph — each bill is single-payer, one-off. (Traded off deliberately for
  scope; see Alternatives Considered.)
- No payment integration (no Venmo/PayPal deep links) — the app's job ends at
  showing "who owes the payer how much."
- No mobile native app — a web app (desktop + mobile browser) covers the use
  case without app-store friction.

## Architecture

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

**Deployment:** backend + Postgres on Railway or Render; frontend on
Vercel/Netlify. Google Vision API key is server-side only, never sent to the
client.

## Data Model

- **Bill** — id, roomCode (short human-friendly code), payerName, subtotal,
  tax, tip, total, status (`DRAFT` | `OPEN` | `CLOSED`), createdAt,
  expiresAt (~48h after creation; no accounts means no long-term ownership,
  so bills are cleaned up after expiry)
- **Item** — id, billId, name, price, quantity
- **Participant** — id, billId, name, sessionToken (random ID stored
  client-side so a page refresh/reconnect re-identifies the same person
  without login)
- **ItemClaim** — join table (itemId, participantId, unitIndex). A line with
  `quantity > 1` exposes one claim slot per unit, so three people can each
  take one of three tacos. Several rows sharing an (itemId, unitIndex) mean
  that unit is split between them; a single row means it is owned outright.
  This one table models both cases without special-casing.

## Receipt OCR & Parsing Pipeline

1. Client uploads the receipt photo via `POST /api/bills` (multipart).
2. Backend sends the image to Google Cloud Vision `TEXT_DETECTION`, which
   returns text fragments with bounding-box coordinates.
3. `ReceiptParser` (the highest-value, most-ownable piece of code in this
   project):
   - Groups fragments into rows by y-coordinate proximity.
   - Per row, regex-matches a trailing price (`\$?\d+\.\d{2}`); everything
     before the match is the item name.
   - Rows matching subtotal/tax/tip/total/change keywords are pulled into
     the `Bill`'s subtotal/tax/tip/total fields instead of becoming items.
   - Rows with no matched price are discarded.
4. Parsed items are returned to the client as an **editable draft** (not yet
   persisted) so the creator can fix OCR misreads — merge a wrapped line,
   delete a junk row, correct a price — before confirming. Real receipts
   produce imperfect OCR; the review step is load-bearing, not optional
   polish.
5. On confirm: bill + items are persisted, a room code is generated, and the
   bill's status moves to `OPEN`.

## Realtime Sync

- On confirm, the bill's STOMP topic `/topic/bills/{roomCode}` becomes live.
- Each participant's client connects via SockJS/STOMP and subscribes on page
  load after joining with a name.
- Claim/unclaim actions go through `POST /api/bills/{roomCode}/claims` (REST,
  not over the socket, to keep the write path simple and testable). The
  server persists the `ItemClaim` row, then broadcasts the updated claim
  state for that item to the topic.
- All connected clients apply the broadcast diff to local state — no
  polling, no full-state refetch on every change.
- **Reconnect handling:** on WebSocket reconnect (e.g. phone screen lock),
  the client issues one `GET /api/bills/{roomCode}` to resync full state,
  then resumes subscribing — so brief disconnects self-heal instead of
  drifting out of sync.

## Settle-Up Calculation

Computed on read (not stored), so it's always consistent with current claim
state:

1. Each item's price ÷ its quantity = one unit's price; that unit's price ÷
   the number of claimers on it = each claimer's share of that unit.
2. Sum a participant's shares across all claimed items → their subtotal
   share.
3. Tax and tip are distributed **proportionally to each participant's
   subtotal share** (not split evenly) — someone who ordered more food pays
   a proportionally larger slice of tax/tip.
4. **Rounding:** all math is done in cents, and no intermediate result is
   rounded — per-unit and per-claimer shares stay exact until the end, or the
   two divisions would compound their error. Only each participant's final
   total is floored to whole cents; the leftover between the bill total and
   the sum of those floors goes to the payer.
5. **Unclaimed items:** surfaced explicitly in the UI (e.g. "2 items
   unclaimed — $6.50") rather than silently redistributed across everyone.
6. Output: per-participant `name → owes $X.XX to <payer>`. Single-payer,
   one-off bills mean there's no multi-payer settlement graph to simplify.

## Frontend Structure

Flow: `UploadReceipt` → `ReviewItems` (editable OCR draft) →
`BillRoom` (room code, live claiming UI, share link) → `Summary` (who owes
what).

- `BillRoom` owns the WebSocket connection and local item/claim state,
  updated by both REST responses and broadcast messages.
- React Query for REST calls (bill fetch, claim mutations); a small local
  store (Zustand or Context) for live-updated claim state. No Redux needed
  at this scope.

## Testing

- **Backend:** JUnit tests for `ReceiptParser`, using captured real Google
  Vision API outputs from a handful of receipt formats as fixtures — this is
  the highest-value test target in the project. JUnit + `@SpringBootTest`
  for claim/calculation logic (proportional tax/tip split, rounding,
  unclaimed-item handling).
- **Frontend:** a handful of component tests (Vitest + Testing Library) for
  the claiming interaction.
- **E2E:** skipped given portfolio scope; optionally one Playwright
  happy-path smoke test for demo confidence.

## Alternatives Considered

- **Realtime transport:** considered a managed realtime backend
  (Firebase/Supabase) and plain polling. Rejected in favor of Spring
  WebSocket/STOMP because it's the more technically substantive, ownable
  piece of work and fits a Spring-focused job search.
- **OCR:** considered AWS Textract's `AnalyzeExpense` (pre-structured line
  items). Rejected in favor of raw OCR text + a custom parser, since writing
  the parser is itself a demonstrable, interesting piece of code.
- **Ongoing group balances + debt-simplification graph:** considered
  (Splitwise-style persistent groups across many bills, minimizing settle-up
  transactions). Rejected for scope — single one-off bills keep the project
  finishable; this is the clearest "cut for time" item if reviving as a
  stretch goal later.

## Open Items for Implementation Planning

- Exact room-code generation scheme (length, character set, collision
  handling).
- Bill expiry cleanup mechanism (scheduled job vs. lazy delete-on-read).
- Specific Postgres hosting choice (Railway vs. Render) — either works, pick
  during setup.
