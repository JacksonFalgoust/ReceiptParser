# Backend Data Model — Design Spec

**Date:** 2026-08-20
**Status:** Approved for planning

## Purpose

Turn the four empty entity stubs (`Bill`, `Item`, `Participant`, `ItemClaim`)
into a real, migration-backed persistence layer: versioned SQL schema, mapped
JPA entities with the relationships the app actually traverses, and the
repository finders the next few features will call.

This closes [TODO.md](../../../TODO.md) section 2. Full domain rationale lives
in [ARCHITECTURE.md](../../../ARCHITECTURE.md); this spec records the
implementation decisions that document leaves open.

## Scope

In scope: Flyway setup, the `V1` migration, four mapped entities, four
repositories, entity-level tests, and the `ddl-auto` switch to `validate`.

Out of scope, deliberately:

- **Room-code generation.** This spec fixes the code's *format* (which
  determines the column type and the `UNIQUE` constraint), but the generator
  and its collision-retry loop have no caller until `BillController` exists.
- **Bill expiry cleanup.** The `expires_at` column lands here. Choosing
  between a scheduled job and lazy delete-on-read is easier once there is a
  real read path to hang lazy deletion off of.

Both remain open in `TODO.md` section 1.

## Decisions

### Flyway migrations, not `ddl-auto: update`

Versioned SQL becomes the source of truth and `ddl-auto` flips to `validate`,
so Hibernate verifies that entities match the schema at boot instead of
mutating it. Three reasons: the schema becomes reviewable in a diff, `update`
never drops or alters columns cleanly, and a real deploy needs migrations
regardless.

Spring Boot 4.x delivers Flyway through a **modular starter** —
`spring-boot-starter-flyway`, not the bare `org.flywaydb:flyway-core`
dependency that Boot 3.x used. PostgreSQL support is a separate runtime
artifact, `flyway-database-postgresql`, and tests use
`spring-boot-starter-flyway-test`. Migrations live in the default
`classpath:db/migration`.

### Money is stored as integer cents

Columns are `BIGINT` and fields are named `priceCents`, `subtotalCents`, and
so on. ARCHITECTURE.md already specifies that settle-up does "all math in
cents" with leftover cents assigned to the payer, so storing cents means zero
conversion in the calculation that matters most, and no `BigDecimal` scale or
`RoundingMode` traps. The parser converts `"$12.34"` → `1234` on the way in;
the API converts back for display.

### `Bill` is the aggregate root

`Bill` holds `@OneToMany` collections of `Item` and `Participant` with cascade
and `orphanRemoval`. Persisting a confirmed OCR draft is one
`billRepository.save(bill)`, and deleting an expired bill cascades. All
`@ManyToOne` sides are `LAZY`.

`ItemClaim` is deliberately **not** a collection on `Item`. Claims churn at
high frequency and are broadcast as diffs, so they are queried through their
own repository rather than dragged in via the object graph.

This means the API edge needs DTOs rather than serializing entities, and the
bill-room `GET` needs a fetch join to avoid N+1 — both of which this project
wants anyway.

### Claiming is per unit, not per line

A receipt line with `quantity > 1` exposes one claim slot per unit, addressed
by `unit_index` on `ItemClaim`. Three people can each take one of three tacos.

The alternative considered was exploding a `×3` line into three separate
`Item` rows at parse time, which would leave `ItemClaim` untouched. Rejected
for two reasons: `$10.00 ÷ 3` forces a rounding decision at parse time, baking
it into stored data and duplicating logic the settle-up layer already owns;
and the resulting rows display as `$3.34 / $3.33 / $3.33` for identical items,
which reads as a bug.

A `units_claimed` count on `ItemClaim` was also considered and rejected: it
needs an application check that claimed units never exceed `quantity`, which
is a read-modify-write race in a realtime multi-client app and would require
optimistic locking to be correct. `unit_index` needs no such check, because
claiming a specific unit is an insert that the unique constraint either
accepts or rejects atomically.

### Room code format: 6 characters, unambiguous alphabet

The alphabet is the digits `2`–`9` plus `A`–`Z` with `I`, `L`, and `O`
removed — 8 digits and 23 letters, 31 characters total. Dropping `0`, `1`,
`I`, `L`, and `O` means no two characters in the set look alike in a
sans-serif font or sound alike when read aloud.

Six characters gives 31⁶ ≈ 887 million combinations, ample for codes that
live 48 hours. Codes are uppercase; input is matched case-insensitively so a
typed lowercase code still resolves. The column is
`VARCHAR(6) NOT NULL UNIQUE`.

## Schema

`backend/src/main/resources/db/migration/V1__create_bill_schema.sql`:

```sql
CREATE TABLE bill (
    id             BIGSERIAL PRIMARY KEY,
    room_code      VARCHAR(6)  NOT NULL UNIQUE,
    payer_name     VARCHAR(100) NOT NULL,
    subtotal_cents BIGINT      NOT NULL DEFAULT 0,
    tax_cents      BIGINT      NOT NULL DEFAULT 0,
    tip_cents      BIGINT      NOT NULL DEFAULT 0,
    total_cents    BIGINT      NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL
                   CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED')),
    created_at     TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL
);

CREATE TABLE item (
    id          BIGSERIAL PRIMARY KEY,
    bill_id     BIGINT       NOT NULL REFERENCES bill (id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    price_cents BIGINT       NOT NULL,
    quantity    INTEGER      NOT NULL DEFAULT 1 CHECK (quantity > 0)
);
CREATE INDEX idx_item_bill_id ON item (bill_id);

CREATE TABLE participant (
    id            BIGSERIAL PRIMARY KEY,
    bill_id       BIGINT       NOT NULL REFERENCES bill (id) ON DELETE CASCADE,
    name          VARCHAR(100) NOT NULL,
    session_token VARCHAR(64)  NOT NULL,
    joined_at     TIMESTAMPTZ  NOT NULL,
    UNIQUE (bill_id, session_token)
);
CREATE INDEX idx_participant_bill_id ON participant (bill_id);

CREATE TABLE item_claim (
    id             BIGSERIAL PRIMARY KEY,
    item_id        BIGINT      NOT NULL REFERENCES item (id) ON DELETE CASCADE,
    participant_id BIGINT      NOT NULL
                   REFERENCES participant (id) ON DELETE CASCADE,
    unit_index     INTEGER     NOT NULL DEFAULT 0 CHECK (unit_index >= 0),
    claimed_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (item_id, participant_id, unit_index)
);
CREATE INDEX idx_item_claim_item_id ON item_claim (item_id);
CREATE INDEX idx_item_claim_participant_id ON item_claim (participant_id);
```

Two constraints carry real weight:

- **`UNIQUE (item_id, participant_id, unit_index)`** makes claiming idempotent
  in the database. A double-tap or a retried request cannot create duplicate
  claim rows, which would otherwise corrupt the per-unit division in
  settle-up.
- **`UNIQUE (bill_id, session_token)`** is what makes reconnect work. The same
  browser rejoining a bill resolves to the existing participant instead of
  spawning a duplicate.

## Field semantics

These readings are not stated in ARCHITECTURE.md and are fixed here.

**`price_cents` is the line total, not the unit price.** The parser
regex-matches the trailing price on a receipt row, which is already the
extended amount.

**`quantity` defines how many claim slots a line has.** A line with
`quantity = 3` exposes units `0`, `1`, and `2`, each independently claimable.
This is the per-unit claiming model: `"3 TACOS  $10.00"` stays one row and
displays as `Tacos ×3 — $10.00`, but three people can each take one taco, or
one person can take all three, or two can share a single unit.

`ItemClaim` still models shared and exclusive claiming without special-casing
— the grain is just finer. Several rows sharing an `(item_id, unit_index)`
means that unit is split; a single row means it is owned outright. When
`quantity = 1`, which is the overwhelmingly common case, `unit_index` is
always `0` and the model behaves exactly as it would without the column.

**Money columns are `NOT NULL DEFAULT 0`.** This gives up the distinction
between "the receipt had no tip line" and "the tip was $0.00". That difference
has no behavioral consequence in this app, and the default keeps null checks
out of the proportional tax/tip split.

**`DRAFT` is in the enum but unreachable under the current flow.**
ARCHITECTURE.md specifies that a parsed draft is returned to the client
*unpersisted*, so every row that reaches the database starts at `OPEN`.
Retaining the value costs nothing and leaves room for server-side draft
persistence later, but the spec is mildly self-inconsistent here and the
implementation should not add a code path that writes a `DRAFT` row.

## Consequences of per-unit claiming

### Rounding must stay a single pass

Per-unit claiming introduces a second division: a line splits across its
units, and each unit splits across its claimers. Rounding at both levels would
compound the error and could make per-participant totals fail to sum to the
bill total.

The rule is that no intermediate result is ever rounded. A participant's exact
share of one item is

```
price_cents / quantity × Σ (1 / claimers_on_unit)   over each unit they claim
```

kept as an exact rational. Those rationals accumulate across items, tax and
tip are applied proportionally as ARCHITECTURE.md requires, and only the final
per-participant total is floored to whole cents. The difference between the
bill total and the sum of the floors is the leftover, which goes to the payer —
exactly the rule ARCHITECTURE.md already states, now applied once at the end
instead of at each division.

Implementing this belongs to the settle-up task, not this one. It is recorded
here because it is the reason the model stores a line total and a quantity
rather than a pre-divided unit price: dividing at parse time would bake
rounding into stored data and duplicate logic the settle-up layer owns.

### One invariant lives in the application layer

`unit_index` must be less than the parent item's `quantity`, and Postgres
cannot express that in a `CHECK` constraint because it spans two tables. The
column constraint stops negatives; the upper bound is the claim endpoint's
responsibility and belongs to that task's validation and tests.

A trigger or a denormalized copy of `quantity` onto `item_claim` could enforce
it in the database, but both cost more than the risk justifies for a
single-writer endpoint that already has to validate the item exists.

### Unclaimed reporting gets finer

ARCHITECTURE.md asks for unclaimed items to be surfaced explicitly. Per-unit
claiming makes this partial: a `×3` line with two units claimed leaves one
unit unclaimed, worth `price_cents / quantity`. The unclaimed report is
computed over units rather than whole lines.

## Entities

Each entity is mapped in its existing package, replacing the stub comment.

- **`Bill`** — scalar fields per the schema; `status` is
  `@Enumerated(EnumType.STRING)`, never `ORDINAL`, so the column stays
  readable and reordering the enum cannot corrupt existing rows.
  `@OneToMany(mappedBy = "bill", cascade = ALL, orphanRemoval = true)` for
  both `items` and `participants`, with `addItem` / `addParticipant` helpers
  that set the owning side so both halves stay consistent in memory.
- **`Item`** — `@ManyToOne(fetch = LAZY) Bill bill`, plus `name`,
  `priceCents`, `quantity`.
- **`Participant`** — `@ManyToOne(fetch = LAZY) Bill bill`, plus `name`,
  `sessionToken`, `joinedAt`.
- **`ItemClaim`** — `@ManyToOne(fetch = LAZY)` to both `Item` and
  `Participant`, plus `unitIndex` and `claimedAt`. No collection points at it.

`equals` / `hashCode` are identity-based on `id` with a constant `hashCode`,
so an entity's hash does not change when it transitions from transient to
persistent and it survives being held in a `HashSet` across a flush.

Timestamps are `Instant`, mapping to `TIMESTAMPTZ`.

## Repositories

Only finders with a near-term caller. Each extends `JpaRepository<T, Long>`.

| Repository | Method | Caller |
|---|---|---|
| `BillRepository` | `Optional<Bill> findByRoomCode(String)` | bill room `GET` |
| | `boolean existsByRoomCode(String)` | room-code generator (next task) |
| `ParticipantRepository` | `Optional<Participant> findByBillIdAndSessionToken(Long, String)` | join / reconnect |
| `ItemClaimRepository` | `List<ItemClaim> findByItemBillId(Long)` | room state + settle-up |
| | `Optional<ItemClaim> findByItemIdAndParticipantIdAndUnitIndex(Long, Long, int)` | claim / unclaim of one unit |
| `ItemRepository` | inherited CRUD only | `getReferenceById` when attaching a claim |

`ItemRepository` earns its place without declaring a single custom method: the
claim path needs an `Item` reference to attach an `ItemClaim` to, and
`getReferenceById` supplies one without loading the row.

`Item.findByBillId` and `Participant.findByBillId` are intentionally omitted:
they duplicate what the `Bill` aggregate's collections already provide.

Room codes are stored uppercase, so callers normalize input with
`toUpperCase(Locale.ROOT)` before calling `findByRoomCode`. Keeping the
normalization in the caller lets the lookup use the plain unique index rather
than an `IgnoreCase` derivation.

## Testing

Tests are written before the implementation, per the repo's TDD workflow. They
run as `@SpringBootTest` against the Docker Postgres, consistent with the
existing suite.

- Saving a `Bill` with items and participants cascades — children receive
  generated IDs.
- Removing an item from the collection deletes the row (`orphanRemoval`).
- Deleting a `Bill` removes its items, participants, and their claims.
- Inserting a duplicate `(item_id, participant_id, unit_index)` violates the
  constraint.
- The same participant claiming two *different* units of one item succeeds —
  the constraint must not be so tight that it blocks "I had two of these."
- Two participants claiming the *same* unit succeeds — a shared unit is legal
  and is what makes `ItemClaim` model sharing without special-casing.
- Inserting a duplicate `(bill_id, session_token)` violates the constraint.
- Inserting a duplicate `room_code` violates the constraint.
- A negative `unit_index` violates the check constraint.
- `status` round-trips as a string, verified by reading the raw column.
- Each repository finder returns the expected row and an empty `Optional` for
  a miss.

`ScaffoldStubsTests` is converted per the instruction in its own comment. The
exact-four-entities assertion still holds and stays.

Boot success is itself a schema test: with `ddl-auto: validate`, any drift
between the entities and the `V1` migration fails the context load.

## Files

**Added**

```
backend/src/main/resources/db/migration/V1__create_bill_schema.sql
backend/src/main/java/.../bill/BillRepository.java
backend/src/main/java/.../bill/BillStatus.java
backend/src/main/java/.../item/ItemRepository.java
backend/src/main/java/.../participant/ParticipantRepository.java
backend/src/main/java/.../claim/ItemClaimRepository.java
```

Plus entity-level test classes under
`backend/src/test/java/com/jacksonfalgoust/receiptsplitter/`.

**Changed**

```
backend/pom.xml                    + spring-boot-starter-flyway,
                                     flyway-database-postgresql (runtime),
                                     spring-boot-starter-flyway-test (test)
backend/src/main/resources/application.yml   ddl-auto: update → validate
backend/src/main/java/.../bill/Bill.java
backend/src/main/java/.../item/Item.java
backend/src/main/java/.../participant/Participant.java
backend/src/main/java/.../claim/ItemClaim.java
backend/src/test/java/.../ScaffoldStubsTests.java
TODO.md                            check off section 2
ARCHITECTURE.md                    ItemClaim grain + settle-up division
```

### ARCHITECTURE.md needs two corrections

Per-unit claiming diverges from the source spec in two places, and leaving
them stale would mislead the settle-up work later:

- The Data Model section describes `ItemClaim` as a join table of
  `(itemId, participantId)`. It now carries `unitIndex`.
- The Settle-Up section says "Each item's price ÷ number of claimers on it."
  The division is now per unit: `price ÷ quantity`, then that unit's share ÷
  its claimers, with the single-rounding rule described above.

These are edits to the existing prose, not new sections. The rest of
ARCHITECTURE.md — including the "no special-casing" claim about `ItemClaim`,
which still holds — stays as written.

## Execution hazard: the existing local database

The local Postgres volume already contains the four stub tables, created by
`ddl-auto: update` during scaffolding. Flyway's `V1` will fail against that
non-empty schema.

The implementation must therefore reset the volume before its first run:

```bash
docker compose down -v && docker compose up -d
```

There is no production data anywhere, so this is safe and does not warrant a
baseline migration. The plan should carry it as an explicit step rather than
leaving it to be discovered as a confusing startup failure.
