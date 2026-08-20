# Backend Data Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the four empty entity stubs with a Flyway-migrated schema, mapped JPA entities supporting per-unit claiming, and the repository finders the next features will call.

**Architecture:** Flyway owns the schema as versioned SQL and Hibernate is demoted to `ddl-auto: validate`, so entity/schema drift fails at boot. `Bill` is the aggregate root holding cascading collections of `Item` and `Participant`; `ItemClaim` stands alone with `@ManyToOne` links to `Item` and `Participant` plus a `unitIndex`, so each unit of a multi-quantity line is independently claimable.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, Flyway 12.4.0, PostgreSQL 16 (Docker), JUnit 5 + AssertJ.

**Spec:** [docs/superpowers/specs/2026-08-20-backend-data-model-design.md](../specs/2026-08-20-backend-data-model-design.md)

## Global Constraints

- **Branch:** `feature/backend-data-model` (already created and published). Never commit to `main`.
- **Commits:** Never add a `Co-Authored-By` trailer.
- **Java version:** 21. **Spring Boot:** 4.1.0 (parent POM).
- **Flyway artifacts are BOM-managed** — declare `spring-boot-starter-flyway`, `spring-boot-starter-flyway-test`, and `org.flywaydb:flyway-database-postgresql` with **no `<version>` tag**. Boot 4.1.0 pins Flyway 12.4.0.
- **Money is `long` cents** everywhere. Field names end in `Cents`. Never `BigDecimal`, never `double`.
- **`price_cents` is the line total**, not a unit price. `quantity` is the number of claim slots.
- **Room code:** exactly 6 characters, `VARCHAR(6)`, stored uppercase. Alphabet is digits `2`–`9` plus `A`–`Z` minus `I`, `L`, `O` (31 characters). The generator is **not** built in this plan. The alphabet is a *generator* rule — the column carries no CHECK constraint for it, so test fixtures below use readable mnemonics like `BILL22` that contain excluded letters. That is intentional and will not fail; do not add an alphabet constraint.
- **Enums persist as strings** — `@Enumerated(EnumType.STRING)`. Never `ORDINAL`.
- **Timestamps are `java.time.Instant`**, mapping to `TIMESTAMPTZ`.
- **Tests run against the real Docker Postgres**, consistent with the existing suite. `docker compose up -d` must be running.
- **Do not build** the room-code generator, the expiry cleanup job, any controller, or any DTO. Those are later tasks.

---

## File Structure

**Created**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V1__create_bill_schema.sql` | The entire schema. Single source of truth. |
| `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/BillStatus.java` | `DRAFT` / `OPEN` / `CLOSED` enum |
| `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/BillRepository.java` | Room-code lookups |
| `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/item/ItemRepository.java` | CRUD + `getReferenceById` for claims |
| `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/participant/ParticipantRepository.java` | Join / reconnect lookup |
| `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/claim/ItemClaimRepository.java` | Claim queries |
| `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/schema/SchemaMigrationTests.java` | Asserts the migration produced the right tables and constraints |
| `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/bill/BillPersistenceTests.java` | Bill mapping + room-code uniqueness |
| `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/item/ItemPersistenceTests.java` | Cascade + orphan removal |
| `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/participant/ParticipantPersistenceTests.java` | Session-token uniqueness |
| `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/claim/ItemClaimPersistenceTests.java` | Per-unit claiming constraints |

**Modified**

| File | Change |
|---|---|
| `backend/pom.xml` | Add three Flyway dependencies |
| `backend/src/main/resources/application.yml` | `ddl-auto: update` → `validate` |
| `.../bill/Bill.java`, `.../item/Item.java`, `.../participant/Participant.java`, `.../claim/ItemClaim.java` | Replace stub comments with real mappings |
| `backend/src/test/java/.../ScaffoldStubsTests.java` | Convert per its own comment |
| `ARCHITECTURE.md` | Two corrections for per-unit claiming |
| `TODO.md` | Check off section 2 |
| `CLAUDE.md` | Update "Project status" |

---

## Task 1: Flyway schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__create_bill_schema.sql`
- Create: `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/schema/SchemaMigrationTests.java`
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: tables `bill`, `item`, `participant`, `item_claim` with the exact column names later tasks map to. Flyway is active for every subsequent test run.

> **Do this first.** The local Postgres volume already contains stub tables created by `ddl-auto: update`. Flyway's `V1` will fail against a non-empty schema. There is no production data anywhere, so resetting the volume is safe.
>
> ```bash
> docker compose down -v && docker compose up -d
> ```

- [ ] **Step 1: Reset the database volume**

Run from the repo root:

```bash
docker compose down -v && docker compose up -d
```

Wait a few seconds for Postgres to accept connections.

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/schema/SchemaMigrationTests.java`:

```java
package com.jacksonfalgoust.receiptsplitter.schema;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// @Transactional so the cascade test's inserted rows roll back. Without it a
// mid-test failure would strand room code CASC22 and the next run would fail
// on the unique constraint instead of the real problem.
@SpringBootTest
@Transactional
class SchemaMigrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationCreatesAllFourTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("bill", "item", "participant", "item_claim");
    }

    @Test
    void flywayRecordsTheBaselineMigration() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true",
                String.class);

        assertThat(versions).contains("1");
    }

    @Test
    void claimUniquenessSpansItemParticipantAndUnitIndex() {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = 'item_claim'
                  AND tc.constraint_type = 'UNIQUE'
                """, String.class);

        assertThat(columns)
                .containsExactlyInAnyOrder("item_id", "participant_id", "unit_index");
    }

    @Test
    void participantUniquenessSpansBillAndSessionToken() {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = 'participant'
                  AND tc.constraint_type = 'UNIQUE'
                """, String.class);

        assertThat(columns).containsExactlyInAnyOrder("bill_id", "session_token");
    }

    @Test
    void deletingABillCascadesToItemsAndClaims() {
        jdbcTemplate.update("""
                INSERT INTO bill (room_code, payer_name, status, created_at, expires_at)
                VALUES ('CASC22', 'Payer', 'OPEN', now(), now() + interval '48 hours')
                """);
        Long billId = jdbcTemplate.queryForObject(
                "SELECT id FROM bill WHERE room_code = 'CASC22'", Long.class);

        jdbcTemplate.update(
                "INSERT INTO item (bill_id, name, price_cents, quantity) "
                        + "VALUES (?, 'Tacos', 1000, 3)", billId);
        Long itemId = jdbcTemplate.queryForObject(
                "SELECT id FROM item WHERE bill_id = ?", Long.class, billId);

        jdbcTemplate.update(
                "INSERT INTO participant (bill_id, name, session_token, joined_at) "
                        + "VALUES (?, 'Ana', 'tok-casc', now())", billId);
        Long participantId = jdbcTemplate.queryForObject(
                "SELECT id FROM participant WHERE bill_id = ?", Long.class, billId);

        jdbcTemplate.update(
                "INSERT INTO item_claim (item_id, participant_id, unit_index, claimed_at) "
                        + "VALUES (?, ?, 0, now())", itemId, participantId);

        jdbcTemplate.update("DELETE FROM bill WHERE id = ?", billId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM item WHERE bill_id = ?", Long.class, billId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM item_claim WHERE item_id = ?", Long.class, itemId)).isZero();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
cd backend && mvn test -Dtest=SchemaMigrationTests
```

Expected: FAIL. Without Flyway on the classpath there is no `flyway_schema_history` table, and the `item_claim` table does not exist.

- [ ] **Step 4: Add the Flyway dependencies**

In `backend/pom.xml`, inside `<dependencies>`, add after the `spring-boot-starter-websocket` block. **No `<version>` tags** — Boot 4.1.0 manages Flyway 12.4.0:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway</artifactId>
		</dependency>
		<dependency>
			<groupId>org.flywaydb</groupId>
			<artifactId>flyway-database-postgresql</artifactId>
			<scope>runtime</scope>
		</dependency>
```

And alongside the other test-scoped starters:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 5: Write the migration**

Create `backend/src/main/resources/db/migration/V1__create_bill_schema.sql`:

```sql
CREATE TABLE bill (
    id             BIGSERIAL PRIMARY KEY,
    room_code      VARCHAR(6)   NOT NULL UNIQUE,
    payer_name     VARCHAR(100) NOT NULL,
    subtotal_cents BIGINT       NOT NULL DEFAULT 0,
    tax_cents      BIGINT       NOT NULL DEFAULT 0,
    tip_cents      BIGINT       NOT NULL DEFAULT 0,
    total_cents    BIGINT       NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL
                   CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED')),
    created_at     TIMESTAMPTZ  NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL
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

- [ ] **Step 6: Switch Hibernate to validate**

In `backend/src/main/resources/application.yml`, change the `ddl-auto` line:

```yaml
spring:
  application:
    name: receipt-splitter
  datasource:
    url: jdbc:postgresql://localhost:5432/receipt_splitter
    username: receipt_splitter
    password: receipt_splitter
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: true
```

- [ ] **Step 7: Run the test to verify it passes**

```bash
cd backend && mvn test -Dtest=SchemaMigrationTests
```

Expected: PASS, 5 tests.

If startup fails with a Flyway "found non-empty schema without schema history table" error, Step 1 was skipped — run it and retry.

- [ ] **Step 8: Run the whole suite**

```bash
cd backend && mvn test
```

Expected: PASS. `ScaffoldStubsTests` still passes because `validate` only checks that mapped columns exist, and the stubs map nothing but `id`.

- [ ] **Step 9: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml \
        backend/src/main/resources/db/migration/V1__create_bill_schema.sql \
        backend/src/test/java/com/jacksonfalgoust/receiptsplitter/schema/SchemaMigrationTests.java
git commit -m "feat(db): add Flyway schema for bills, items, participants, claims

Flyway becomes the schema source of truth and Hibernate drops to
ddl-auto: validate, so entity drift fails at boot instead of silently
mutating the database.

item_claim carries unit_index so each unit of a multi-quantity line is
independently claimable, with UNIQUE (item_id, participant_id, unit_index)
making a claim idempotent at the database level."
```

---

## Task 2: Bill entity and repository

**Files:**
- Modify: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/Bill.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/BillStatus.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/BillRepository.java`
- Create: `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/bill/BillPersistenceTests.java`

**Interfaces:**
- Consumes: the `bill` table from Task 1.
- Produces:
  - `BillStatus` enum — `DRAFT`, `OPEN`, `CLOSED`.
  - `Bill(String roomCode, String payerName, BillStatus status, Instant createdAt, Instant expiresAt)` constructor.
  - Getters/setters: `getId()`, `getRoomCode()`, `getPayerName()`, `getSubtotalCents()`/`setSubtotalCents(long)`, `getTaxCents()`/`setTaxCents(long)`, `getTipCents()`/`setTipCents(long)`, `getTotalCents()`/`setTotalCents(long)`, `getStatus()`/`setStatus(BillStatus)`, `getCreatedAt()`, `getExpiresAt()`.
  - `BillRepository.findByRoomCode(String)` → `Optional<Bill>`, `BillRepository.existsByRoomCode(String)` → `boolean`.

> Tasks 3 and 4 add the `items` and `participants` collections. Do **not** add them here — `mappedBy` would reference fields that do not exist yet and the module would not compile.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/bill/BillPersistenceTests.java`:

```java
package com.jacksonfalgoust.receiptsplitter.bill;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class BillPersistenceTests {

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private EntityManager entityManager;

    private static Bill newBill(String roomCode) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new Bill(roomCode, "Ana", BillStatus.OPEN, now, now.plus(48, ChronoUnit.HOURS));
    }

    @Test
    void savesAndReloadsAllScalarFields() {
        Bill bill = newBill("BILL22");
        bill.setSubtotalCents(1000L);
        bill.setTaxCents(85L);
        bill.setTipCents(200L);
        bill.setTotalCents(1285L);

        Bill saved = billRepository.saveAndFlush(bill);
        entityManager.clear();

        Bill reloaded = billRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getRoomCode()).isEqualTo("BILL22");
        assertThat(reloaded.getPayerName()).isEqualTo("Ana");
        assertThat(reloaded.getSubtotalCents()).isEqualTo(1000L);
        assertThat(reloaded.getTaxCents()).isEqualTo(85L);
        assertThat(reloaded.getTipCents()).isEqualTo(200L);
        assertThat(reloaded.getTotalCents()).isEqualTo(1285L);
        assertThat(reloaded.getStatus()).isEqualTo(BillStatus.OPEN);
    }

    @Test
    void moneyDefaultsToZero() {
        Bill saved = billRepository.saveAndFlush(newBill("ZERO22"));
        entityManager.clear();

        Bill reloaded = billRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSubtotalCents()).isZero();
        assertThat(reloaded.getTaxCents()).isZero();
        assertThat(reloaded.getTipCents()).isZero();
        assertThat(reloaded.getTotalCents()).isZero();
    }

    @Test
    void statusIsStoredAsAStringNotAnOrdinal() {
        Bill saved = billRepository.saveAndFlush(newBill("ENUM22"));

        Object raw = entityManager
                .createNativeQuery("SELECT status FROM bill WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(raw).isEqualTo("OPEN");
    }

    @Test
    void findsByRoomCode() {
        billRepository.saveAndFlush(newBill("FIND22"));
        entityManager.clear();

        assertThat(billRepository.findByRoomCode("FIND22"))
                .isPresent()
                .get()
                .extracting(Bill::getPayerName)
                .isEqualTo("Ana");
    }

    @Test
    void returnsEmptyForAnUnknownRoomCode() {
        assertThat(billRepository.findByRoomCode("NOPE22")).isEmpty();
    }

    @Test
    void reportsWhetherARoomCodeIsTaken() {
        billRepository.saveAndFlush(newBill("TAKEN2"));

        assertThat(billRepository.existsByRoomCode("TAKEN2")).isTrue();
        assertThat(billRepository.existsByRoomCode("FREE22")).isFalse();
    }

    @Test
    void rejectsADuplicateRoomCode() {
        billRepository.saveAndFlush(newBill("DUPE22"));

        assertThatThrownBy(() -> billRepository.saveAndFlush(newBill("DUPE22")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

> Each constraint-violation assertion lives in its own test method on purpose. A failed constraint poisons the transaction, so any further database work in the same method would fail for the wrong reason.

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend && mvn test -Dtest=BillPersistenceTests
```

Expected: compilation FAILS — `BillStatus`, `BillRepository`, and the `Bill` constructor do not exist.

- [ ] **Step 3: Create the status enum**

Create `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/BillStatus.java`:

```java
package com.jacksonfalgoust.receiptsplitter.bill;

/**
 * Lifecycle of a bill.
 *
 * <p>{@code DRAFT} is currently unreachable: a parsed receipt is returned to
 * the client for editing without being persisted, so every row that reaches
 * the database starts at {@code OPEN}. The value is retained for a future
 * server-side draft flow.
 */
public enum BillStatus {
    DRAFT,
    OPEN,
    CLOSED
}
```

- [ ] **Step 4: Map the Bill entity**

Replace the whole of `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/Bill.java`:

```java
package com.jacksonfalgoust.receiptsplitter.bill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "bill")
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_code", nullable = false, unique = true, length = 6)
    private String roomCode;

    @Column(name = "payer_name", nullable = false, length = 100)
    private String payerName;

    @Column(name = "subtotal_cents", nullable = false)
    private long subtotalCents;

    @Column(name = "tax_cents", nullable = false)
    private long taxCents;

    @Column(name = "tip_cents", nullable = false)
    private long tipCents;

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BillStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected Bill() {
        // for JPA
    }

    public Bill(String roomCode, String payerName, BillStatus status,
                Instant createdAt, Instant expiresAt) {
        this.roomCode = roomCode;
        this.payerName = payerName;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public String getPayerName() {
        return payerName;
    }

    public long getSubtotalCents() {
        return subtotalCents;
    }

    public void setSubtotalCents(long subtotalCents) {
        this.subtotalCents = subtotalCents;
    }

    public long getTaxCents() {
        return taxCents;
    }

    public void setTaxCents(long taxCents) {
        this.taxCents = taxCents;
    }

    public long getTipCents() {
        return tipCents;
    }

    public void setTipCents(long tipCents) {
        this.tipCents = tipCents;
    }

    public long getTotalCents() {
        return totalCents;
    }

    public void setTotalCents(long totalCents) {
        this.totalCents = totalCents;
    }

    public BillStatus getStatus() {
        return status;
    }

    public void setStatus(BillStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Bill other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        // Constant so an entity's hash does not change when it goes from
        // transient to persistent, keeping it findable in a HashSet.
        return getClass().hashCode();
    }
}
```

- [ ] **Step 5: Create the repository**

Create `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/BillRepository.java`:

```java
package com.jacksonfalgoust.receiptsplitter.bill;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BillRepository extends JpaRepository<Bill, Long> {

    /**
     * Room codes are stored uppercase; callers normalize input with
     * {@code toUpperCase(Locale.ROOT)} so this lookup uses the unique index.
     */
    Optional<Bill> findByRoomCode(String roomCode);

    boolean existsByRoomCode(String roomCode);
}
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
cd backend && mvn test -Dtest=BillPersistenceTests
```

Expected: PASS, 7 tests.

If startup fails with a Hibernate schema-validation error about `created_at` or `expires_at` wanting a different type, add this under `spring.jpa` in `application.yml` and re-run:

```yaml
    properties:
      hibernate:
        type:
          preferred_instant_jdbc_type: TIMESTAMP_UTC
```

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/ \
        backend/src/test/java/com/jacksonfalgoust/receiptsplitter/bill/
git commit -m "feat(bill): map Bill entity with room code and cents columns

Money is stored as long cents so the settle-up calculation needs no
conversion and cannot hit BigDecimal scale or rounding-mode traps.
Status persists as a string, never an ordinal, so reordering the enum
cannot corrupt existing rows."
```

---

## Task 3: Item entity, cascade from Bill

**Files:**
- Modify: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/item/Item.java`
- Modify: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/Bill.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/item/ItemRepository.java`
- Create: `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/item/ItemPersistenceTests.java`

**Interfaces:**
- Consumes: `Bill`, `BillStatus`, `BillRepository` from Task 2.
- Produces:
  - `Item(String name, long priceCents, int quantity)` constructor.
  - Getters: `getId()`, `getBill()`, `getName()`, `getPriceCents()`, `getQuantity()`; setters `setName(String)`, `setPriceCents(long)`, `setQuantity(int)`, `setBill(Bill)`.
  - `Bill.getItems()` → `List<Item>`, `Bill.addItem(Item)`, `Bill.removeItem(Item)`.
  - `ItemRepository extends JpaRepository<Item, Long>` — no custom finders.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/item/ItemPersistenceTests.java`:

```java
package com.jacksonfalgoust.receiptsplitter.item;

import com.jacksonfalgoust.receiptsplitter.bill.Bill;
import com.jacksonfalgoust.receiptsplitter.bill.BillRepository;
import com.jacksonfalgoust.receiptsplitter.bill.BillStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ItemPersistenceTests {

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private EntityManager entityManager;

    private static Bill newBill(String roomCode) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new Bill(roomCode, "Ana", BillStatus.OPEN, now, now.plus(48, ChronoUnit.HOURS));
    }

    @Test
    void savingABillCascadesToItsItems() {
        Bill bill = newBill("ITEM22");
        bill.addItem(new Item("Tacos", 1000L, 3));
        bill.addItem(new Item("Horchata", 350L, 1));

        Bill saved = billRepository.saveAndFlush(bill);
        entityManager.clear();

        Bill reloaded = billRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getItems())
                .hasSize(2)
                .allSatisfy(item -> assertThat(item.getId()).isNotNull())
                .extracting(Item::getName)
                .containsExactlyInAnyOrder("Tacos", "Horchata");
    }

    @Test
    void addItemSetsBothSidesOfTheRelationship() {
        Bill bill = newBill("BOTH22");
        Item item = new Item("Tacos", 1000L, 3);
        bill.addItem(item);

        assertThat(item.getBill()).isSameAs(bill);
        assertThat(bill.getItems()).containsExactly(item);
    }

    @Test
    void priceIsTheLineTotalAndQuantityIsTheClaimSlotCount() {
        Bill bill = newBill("LINE22");
        bill.addItem(new Item("Tacos", 1000L, 3));

        Bill saved = billRepository.saveAndFlush(bill);
        entityManager.clear();

        Item reloaded = billRepository.findById(saved.getId()).orElseThrow()
                .getItems().getFirst();
        // 1000 is the whole line, not the price of one taco.
        assertThat(reloaded.getPriceCents()).isEqualTo(1000L);
        assertThat(reloaded.getQuantity()).isEqualTo(3);
    }

    @Test
    void quantityDefaultsToOne() {
        Bill bill = newBill("QTY122");
        bill.addItem(new Item("Horchata", 350L, 1));

        Bill saved = billRepository.saveAndFlush(bill);
        entityManager.clear();

        assertThat(billRepository.findById(saved.getId()).orElseThrow()
                .getItems().getFirst().getQuantity()).isEqualTo(1);
    }

    @Test
    void removingAnItemFromTheCollectionDeletesTheRow() {
        Bill bill = newBill("ORPH22");
        bill.addItem(new Item("Tacos", 1000L, 3));
        bill.addItem(new Item("Horchata", 350L, 1));
        Bill saved = billRepository.saveAndFlush(bill);

        Item toRemove = saved.getItems().stream()
                .filter(item -> item.getName().equals("Horchata"))
                .findFirst()
                .orElseThrow();
        Long removedId = toRemove.getId();
        saved.removeItem(toRemove);
        billRepository.saveAndFlush(saved);
        entityManager.clear();

        assertThat(itemRepository.findById(removedId)).isEmpty();
        assertThat(billRepository.findById(saved.getId()).orElseThrow().getItems())
                .hasSize(1);
    }

    @Test
    void deletingABillDeletesItsItems() {
        Bill bill = newBill("DEL122");
        bill.addItem(new Item("Tacos", 1000L, 3));
        Bill saved = billRepository.saveAndFlush(bill);
        Long itemId = saved.getItems().getFirst().getId();

        billRepository.delete(saved);
        billRepository.flush();
        entityManager.clear();

        assertThat(itemRepository.findById(itemId)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend && mvn test -Dtest=ItemPersistenceTests
```

Expected: compilation FAILS — `ItemRepository`, `Item`'s constructor, and `Bill.addItem` do not exist.

- [ ] **Step 3: Map the Item entity**

Replace the whole of `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/item/Item.java`:

```java
package com.jacksonfalgoust.receiptsplitter.item;

import com.jacksonfalgoust.receiptsplitter.bill.Bill;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One line of a receipt.
 *
 * <p>{@code priceCents} is the line total, not a unit price — the parser reads
 * the trailing amount on a receipt row, which is already extended. A line with
 * {@code quantity > 1} therefore exposes that many independently claimable
 * units; see {@code ItemClaim.unitIndex}.
 */
@Entity
@Table(name = "item")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected Item() {
        // for JPA
    }

    public Item(String name, long priceCents, int quantity) {
        this.name = name;
        this.priceCents = priceCents;
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public Bill getBill() {
        return bill;
    }

    public void setBill(Bill bill) {
        this.bill = bill;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public void setPriceCents(long priceCents) {
        this.priceCents = priceCents;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Item other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

- [ ] **Step 4: Add the items collection to Bill**

In `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/Bill.java`, add these imports:

```java
import com.jacksonfalgoust.receiptsplitter.item.Item;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;

import java.util.ArrayList;
import java.util.List;
```

Add the field after `expiresAt`:

```java
    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Item> items = new ArrayList<>();
```

And add these methods before `equals`:

```java
    public List<Item> getItems() {
        return items;
    }

    /** Adds an item and sets the owning side so both halves stay consistent. */
    public void addItem(Item item) {
        items.add(item);
        item.setBill(this);
    }

    public void removeItem(Item item) {
        items.remove(item);
        item.setBill(null);
    }
```

- [ ] **Step 5: Create the repository**

Create `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/item/ItemRepository.java`:

```java
package com.jacksonfalgoust.receiptsplitter.item;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * No custom finders: items are reached through the {@code Bill} aggregate.
 * This exists so the claim path can call {@code getReferenceById} to attach an
 * {@code ItemClaim} without loading the row.
 */
public interface ItemRepository extends JpaRepository<Item, Long> {
}
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
cd backend && mvn test -Dtest=ItemPersistenceTests
```

Expected: PASS, 6 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/jacksonfalgoust/receiptsplitter/item/ \
        backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/Bill.java \
        backend/src/test/java/com/jacksonfalgoust/receiptsplitter/item/
git commit -m "feat(item): map Item under the Bill aggregate

Bill owns items with cascade and orphan removal, so persisting a confirmed
OCR draft is a single save and deleting an expired bill takes its items
with it. priceCents is the line total and quantity is the number of
claimable units."
```

---

## Task 4: Participant entity, session-token identity

**Files:**
- Modify: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/participant/Participant.java`
- Modify: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/Bill.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/participant/ParticipantRepository.java`
- Create: `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/participant/ParticipantPersistenceTests.java`

**Interfaces:**
- Consumes: `Bill`, `BillStatus`, `BillRepository` from Task 2.
- Produces:
  - `Participant(String name, String sessionToken, Instant joinedAt)` constructor.
  - Getters: `getId()`, `getBill()`, `getName()`, `getSessionToken()`, `getJoinedAt()`; setters `setName(String)`, `setBill(Bill)`.
  - `Bill.getParticipants()` → `List<Participant>`, `Bill.addParticipant(Participant)`.
  - `ParticipantRepository.findByBillIdAndSessionToken(Long, String)` → `Optional<Participant>`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/participant/ParticipantPersistenceTests.java`:

```java
package com.jacksonfalgoust.receiptsplitter.participant;

import com.jacksonfalgoust.receiptsplitter.bill.Bill;
import com.jacksonfalgoust.receiptsplitter.bill.BillRepository;
import com.jacksonfalgoust.receiptsplitter.bill.BillStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ParticipantPersistenceTests {

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private EntityManager entityManager;

    private static Bill newBill(String roomCode) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return new Bill(roomCode, "Ana", BillStatus.OPEN, now, now.plus(48, ChronoUnit.HOURS));
    }

    private static Participant newParticipant(String name, String token) {
        return new Participant(name, token, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void savingABillCascadesToItsParticipants() {
        Bill bill = newBill("PART22");
        bill.addParticipant(newParticipant("Ana", "tok-ana"));
        bill.addParticipant(newParticipant("Ben", "tok-ben"));

        Bill saved = billRepository.saveAndFlush(bill);
        entityManager.clear();

        assertThat(billRepository.findById(saved.getId()).orElseThrow().getParticipants())
                .hasSize(2)
                .extracting(Participant::getName)
                .containsExactlyInAnyOrder("Ana", "Ben");
    }

    @Test
    void addParticipantSetsBothSidesOfTheRelationship() {
        Bill bill = newBill("SIDE22");
        Participant participant = newParticipant("Ana", "tok-ana");
        bill.addParticipant(participant);

        assertThat(participant.getBill()).isSameAs(bill);
        assertThat(bill.getParticipants()).containsExactly(participant);
    }

    @Test
    void findsAParticipantByBillAndSessionTokenSoAReconnectReidentifies() {
        Bill bill = newBill("RECON2");
        bill.addParticipant(newParticipant("Ana", "tok-ana"));
        Bill saved = billRepository.saveAndFlush(bill);
        entityManager.clear();

        assertThat(participantRepository
                .findByBillIdAndSessionToken(saved.getId(), "tok-ana"))
                .isPresent()
                .get()
                .extracting(Participant::getName)
                .isEqualTo("Ana");
    }

    @Test
    void returnsEmptyForAnUnknownSessionToken() {
        Bill saved = billRepository.saveAndFlush(newBill("MISS22"));

        assertThat(participantRepository
                .findByBillIdAndSessionToken(saved.getId(), "tok-nobody")).isEmpty();
    }

    @Test
    void theSameTokenMayJoinTwoDifferentBills() {
        Bill first = newBill("MULT12");
        first.addParticipant(newParticipant("Ana", "tok-shared"));
        billRepository.saveAndFlush(first);

        Bill second = newBill("MULT22");
        second.addParticipant(newParticipant("Ana", "tok-shared"));

        assertThat(billRepository.saveAndFlush(second).getParticipants()).hasSize(1);
    }

    @Test
    void rejectsTheSameSessionTokenTwiceOnOneBill() {
        Bill bill = newBill("DUPT22");
        bill.addParticipant(newParticipant("Ana", "tok-dupe"));
        bill.addParticipant(newParticipant("Ana on her tablet", "tok-dupe"));

        assertThatThrownBy(() -> billRepository.saveAndFlush(bill))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingABillDeletesItsParticipants() {
        Bill bill = newBill("DELP22");
        bill.addParticipant(newParticipant("Ana", "tok-ana"));
        Bill saved = billRepository.saveAndFlush(bill);
        Long participantId = saved.getParticipants().getFirst().getId();

        billRepository.delete(saved);
        billRepository.flush();
        entityManager.clear();

        assertThat(participantRepository.findById(participantId)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend && mvn test -Dtest=ParticipantPersistenceTests
```

Expected: compilation FAILS — `ParticipantRepository`, `Participant`'s constructor, and `Bill.addParticipant` do not exist.

- [ ] **Step 3: Map the Participant entity**

Replace the whole of `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/participant/Participant.java`:

```java
package com.jacksonfalgoust.receiptsplitter.participant;

import com.jacksonfalgoust.receiptsplitter.bill.Bill;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Someone splitting a bill. There are no accounts: {@code sessionToken} is a
 * random id the client stores, and it is what re-identifies the same person
 * after a refresh or a dropped WebSocket rather than a login.
 */
@Entity
@Table(name = "participant")
public class Participant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "session_token", nullable = false, length = 64)
    private String sessionToken;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected Participant() {
        // for JPA
    }

    public Participant(String name, String sessionToken, Instant joinedAt) {
        this.name = name;
        this.sessionToken = sessionToken;
        this.joinedAt = joinedAt;
    }

    public Long getId() {
        return id;
    }

    public Bill getBill() {
        return bill;
    }

    public void setBill(Bill bill) {
        this.bill = bill;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Participant other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

- [ ] **Step 4: Add the participants collection to Bill**

In `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/Bill.java`, add the import:

```java
import com.jacksonfalgoust.receiptsplitter.participant.Participant;
```

Add the field after `items`:

```java
    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Participant> participants = new ArrayList<>();
```

Add these methods after `removeItem`:

```java
    public List<Participant> getParticipants() {
        return participants;
    }

    /** Adds a participant and sets the owning side so both halves stay consistent. */
    public void addParticipant(Participant participant) {
        participants.add(participant);
        participant.setBill(this);
    }
```

- [ ] **Step 5: Create the repository**

Create `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/participant/ParticipantRepository.java`:

```java
package com.jacksonfalgoust.receiptsplitter.participant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {

    /** Re-identifies a returning browser on reconnect, in place of a login. */
    Optional<Participant> findByBillIdAndSessionToken(Long billId, String sessionToken);
}
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
cd backend && mvn test -Dtest=ParticipantPersistenceTests
```

Expected: PASS, 7 tests.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/jacksonfalgoust/receiptsplitter/participant/ \
        backend/src/main/java/com/jacksonfalgoust/receiptsplitter/bill/Bill.java \
        backend/src/test/java/com/jacksonfalgoust/receiptsplitter/participant/
git commit -m "feat(participant): map Participant with session-token identity

UNIQUE (bill_id, session_token) is what makes reconnect work: the same
browser rejoining resolves to the existing participant instead of
spawning a duplicate. The same token may still join different bills."
```

---

## Task 5: ItemClaim and per-unit claiming

**Files:**
- Modify: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/claim/ItemClaim.java`
- Create: `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/claim/ItemClaimRepository.java`
- Create: `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/claim/ItemClaimPersistenceTests.java`

**Interfaces:**
- Consumes: `Bill`, `Item`, `Participant` and their repositories from Tasks 2–4.
- Produces:
  - `ItemClaim(Item item, Participant participant, int unitIndex, Instant claimedAt)` constructor.
  - Getters: `getId()`, `getItem()`, `getParticipant()`, `getUnitIndex()`, `getClaimedAt()`.
  - `ItemClaimRepository.findByItemBillId(Long)` → `List<ItemClaim>`.
  - `ItemClaimRepository.findByItemIdAndParticipantIdAndUnitIndex(Long, Long, int)` → `Optional<ItemClaim>`.

> This is the task the per-unit design exists for. Three behaviours must all hold at once: the same participant may claim **different** units of one line, two participants may share the **same** unit, and nobody may claim the **same** unit twice. Get all three green.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/claim/ItemClaimPersistenceTests.java`:

```java
package com.jacksonfalgoust.receiptsplitter.claim;

import com.jacksonfalgoust.receiptsplitter.bill.Bill;
import com.jacksonfalgoust.receiptsplitter.bill.BillRepository;
import com.jacksonfalgoust.receiptsplitter.bill.BillStatus;
import com.jacksonfalgoust.receiptsplitter.item.Item;
import com.jacksonfalgoust.receiptsplitter.participant.Participant;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ItemClaimPersistenceTests {

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private ItemClaimRepository itemClaimRepository;

    @Autowired
    private EntityManager entityManager;

    private Bill bill;
    private Item tacos;
    private Participant ana;
    private Participant ben;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        bill = new Bill("CLAIM2", "Ana", BillStatus.OPEN, now, now.plus(48, ChronoUnit.HOURS));
        tacos = new Item("Tacos", 1000L, 3);
        bill.addItem(tacos);
        ana = new Participant("Ana", "tok-ana", now);
        ben = new Participant("Ben", "tok-ben", now);
        bill.addParticipant(ana);
        bill.addParticipant(ben);
        bill = billRepository.saveAndFlush(bill);
        tacos = bill.getItems().getFirst();
        ana = bill.getParticipants().stream()
                .filter(p -> p.getName().equals("Ana")).findFirst().orElseThrow();
        ben = bill.getParticipants().stream()
                .filter(p -> p.getName().equals("Ben")).findFirst().orElseThrow();
    }

    private ItemClaim claim(Participant participant, int unitIndex) {
        return new ItemClaim(tacos, participant, unitIndex,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void savesAndReloadsAClaim() {
        ItemClaim saved = itemClaimRepository.saveAndFlush(claim(ana, 0));
        entityManager.clear();

        ItemClaim reloaded = itemClaimRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUnitIndex()).isZero();
        assertThat(reloaded.getItem().getId()).isEqualTo(tacos.getId());
        assertThat(reloaded.getParticipant().getId()).isEqualTo(ana.getId());
    }

    @Test
    void oneParticipantMayClaimSeveralUnitsOfTheSameLine() {
        itemClaimRepository.saveAndFlush(claim(ana, 0));
        itemClaimRepository.saveAndFlush(claim(ana, 1));
        entityManager.clear();

        assertThat(itemClaimRepository.findByItemBillId(bill.getId()))
                .hasSize(2)
                .extracting(ItemClaim::getUnitIndex)
                .containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void twoParticipantsMayShareTheSameUnit() {
        itemClaimRepository.saveAndFlush(claim(ana, 0));
        itemClaimRepository.saveAndFlush(claim(ben, 0));
        entityManager.clear();

        assertThat(itemClaimRepository.findByItemBillId(bill.getId())).hasSize(2);
    }

    @Test
    void rejectsTheSameParticipantClaimingOneUnitTwice() {
        itemClaimRepository.saveAndFlush(claim(ana, 0));

        assertThatThrownBy(() -> itemClaimRepository.saveAndFlush(claim(ana, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsANegativeUnitIndex() {
        assertThatThrownBy(() -> itemClaimRepository.saveAndFlush(claim(ana, -1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsAllClaimsForABill() {
        itemClaimRepository.saveAndFlush(claim(ana, 0));
        itemClaimRepository.saveAndFlush(claim(ben, 1));
        entityManager.clear();

        assertThat(itemClaimRepository.findByItemBillId(bill.getId())).hasSize(2);
    }

    @Test
    void findsOneClaimForUnclaiming() {
        itemClaimRepository.saveAndFlush(claim(ana, 1));
        entityManager.clear();

        assertThat(itemClaimRepository.findByItemIdAndParticipantIdAndUnitIndex(
                tacos.getId(), ana.getId(), 1)).isPresent();
        assertThat(itemClaimRepository.findByItemIdAndParticipantIdAndUnitIndex(
                tacos.getId(), ana.getId(), 2)).isEmpty();
    }

    @Test
    void deletingABillDeletesItsClaims() {
        ItemClaim saved = itemClaimRepository.saveAndFlush(claim(ana, 0));
        Long claimId = saved.getId();

        billRepository.delete(bill);
        billRepository.flush();
        entityManager.clear();

        assertThat(itemClaimRepository.findById(claimId)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd backend && mvn test -Dtest=ItemClaimPersistenceTests
```

Expected: compilation FAILS — `ItemClaimRepository` and `ItemClaim`'s constructor do not exist.

- [ ] **Step 3: Map the ItemClaim entity**

Replace the whole of `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/claim/ItemClaim.java`:

```java
package com.jacksonfalgoust.receiptsplitter.claim;

import com.jacksonfalgoust.receiptsplitter.item.Item;
import com.jacksonfalgoust.receiptsplitter.participant.Participant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One participant's claim on one unit of one item.
 *
 * <p>A line with {@code quantity > 1} exposes a claim slot per unit, addressed
 * by {@code unitIndex}. Several rows sharing an {@code (item, unitIndex)} mean
 * that unit is split between people; a single row means it is owned outright —
 * so shared and exclusive claiming need no special-casing. When
 * {@code quantity == 1}, {@code unitIndex} is always 0.
 *
 * <p>{@code unitIndex} must be less than the parent item's {@code quantity}.
 * That spans two tables, so Postgres cannot express it as a CHECK constraint;
 * the claim endpoint enforces the upper bound.
 */
@Entity
@Table(name = "item_claim", uniqueConstraints = @UniqueConstraint(
        columnNames = {"item_id", "participant_id", "unit_index"}))
public class ItemClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant participant;

    @Column(name = "unit_index", nullable = false)
    private int unitIndex;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    protected ItemClaim() {
        // for JPA
    }

    public ItemClaim(Item item, Participant participant, int unitIndex, Instant claimedAt) {
        this.item = item;
        this.participant = participant;
        this.unitIndex = unitIndex;
        this.claimedAt = claimedAt;
    }

    public Long getId() {
        return id;
    }

    public Item getItem() {
        return item;
    }

    public Participant getParticipant() {
        return participant;
    }

    public int getUnitIndex() {
        return unitIndex;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ItemClaim other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

- [ ] **Step 4: Create the repository**

Create `backend/src/main/java/com/jacksonfalgoust/receiptsplitter/claim/ItemClaimRepository.java`:

```java
package com.jacksonfalgoust.receiptsplitter.claim;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemClaimRepository extends JpaRepository<ItemClaim, Long> {

    /** Every claim on a bill, for room state and the settle-up calculation. */
    List<ItemClaim> findByItemBillId(Long billId);

    /** One participant's claim on one unit, for unclaiming. */
    Optional<ItemClaim> findByItemIdAndParticipantIdAndUnitIndex(
            Long itemId, Long participantId, int unitIndex);
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd backend && mvn test -Dtest=ItemClaimPersistenceTests
```

Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/jacksonfalgoust/receiptsplitter/claim/ \
        backend/src/test/java/com/jacksonfalgoust/receiptsplitter/claim/
git commit -m "feat(claim): map ItemClaim with per-unit claiming

unit_index makes each unit of a multi-quantity line independently
claimable, so three people can each take one of three tacos. Rows sharing
an (item, unit_index) mean that unit is split; one row means it is owned
outright, so ItemClaim still models both without special-casing.

UNIQUE (item_id, participant_id, unit_index) makes claiming idempotent in
the database, which is what keeps a double-tap or a retried request from
corrupting the per-unit division in settle-up."
```

---

## Task 6: Reconcile the docs and the scaffold test

**Files:**
- Modify: `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/ScaffoldStubsTests.java`
- Modify: `ARCHITECTURE.md`
- Modify: `TODO.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Convert the scaffold test**

The stub-era comment in `ScaffoldStubsTests` asks for this conversion once real fields land. The exact-four-entities assertion still holds and stays; only the framing changes. Replace the whole of `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/ScaffoldStubsTests.java` with a renamed file.

Delete the old file and create `backend/src/test/java/com/jacksonfalgoust/receiptsplitter/DomainMappingTests.java`:

```java
package com.jacksonfalgoust.receiptsplitter;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DomainMappingTests {

    @Autowired
    private EntityManager entityManager;

    @Test
    void jpaRecognizesAllFourDomainEntities() {
        Set<String> entityNames = entityManager.getMetamodel().getEntities().stream()
                .map(type -> type.getJavaType().getSimpleName())
                .collect(Collectors.toSet());

        // Update this list when adding a new @Entity class — this assertion is
        // intentionally exact, not a "contains at least" check.
        assertThat(entityNames)
                .containsExactlyInAnyOrder("Bill", "Item", "Participant", "ItemClaim");
    }
}
```

Run:

```bash
git rm backend/src/test/java/com/jacksonfalgoust/receiptsplitter/ScaffoldStubsTests.java
```

- [ ] **Step 2: Correct ARCHITECTURE.md**

In the **Data Model** section, replace the `ItemClaim` bullet:

```markdown
- **ItemClaim** — join table (itemId, participantId, unitIndex). A line with
  `quantity > 1` exposes one claim slot per unit, so three people can each
  take one of three tacos. Several rows sharing an (itemId, unitIndex) mean
  that unit is split between them; a single row means it is owned outright.
  This one table models both cases without special-casing.
```

In the **Settle-Up Calculation** section, replace step 1:

```markdown
1. Each item's price ÷ its quantity = one unit's price; that unit's price ÷
   the number of claimers on it = each claimer's share of that unit.
```

And replace step 4:

```markdown
4. **Rounding:** all math is done in cents, and no intermediate result is
   rounded — per-unit and per-claimer shares stay exact until the end, or the
   two divisions would compound their error. Only each participant's final
   total is floored to whole cents; the leftover between the bill total and
   the sum of those floors goes to the payer.
```

- [ ] **Step 3: Correct TODO.md**

In section 2, check off every box and replace the four entity bullets with their real state:

```markdown
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
```

In section 1, leave the room-code and expiry items unchecked, and append a note under the room-code bullet:

```markdown
- [ ] Room-code generation scheme (length, charset, collision handling)
      — format settled (6 chars; digits 2-9 and A-Z minus I/L/O); the
      generator and its collision retry are still to build
```

- [ ] **Step 4: Update the project status in CLAUDE.md**

Replace the first paragraph of the "Project status" section:

```markdown
The backend domain model is implemented: Flyway owns the schema
(`backend/src/main/resources/db/migration/`), Hibernate runs at
`ddl-auto: validate`, and `Bill`, `Item`, `Participant`, and `ItemClaim`
are mapped with repositories and persistence tests. Claiming is per unit —
a line with `quantity > 1` exposes one claim slot per unit via
`ItemClaim.unitIndex`. There is still no `ReceiptParser` logic, no
controller behaviour, and no frontend routing; see `TODO.md`.
```

Then add to the "Local setup" block, after the `docker compose up -d` line:

```markdown
Flyway migrates on boot. If Flyway reports a non-empty schema without a
history table, the volume predates the migrations — run
`docker compose down -v && docker compose up -d` to reset it.
```

- [ ] **Step 5: Run the full suite**

```bash
cd backend && mvn test
```

Expected: PASS. All persistence tests plus `DomainMappingTests` and `ReceiptSplitterApplicationTests`.

- [ ] **Step 6: Verify coverage still reports**

```bash
cd backend && mvn verify
```

Expected: BUILD SUCCESS, JaCoCo report written to `backend/target/site/jacoco/`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/test/java/com/jacksonfalgoust/receiptsplitter/ \
        ARCHITECTURE.md TODO.md CLAUDE.md
git commit -m "docs: reconcile specs and TODO with the implemented data model

ARCHITECTURE.md described ItemClaim as (itemId, participantId) and
settle-up as a single division by claimer count. Both predate per-unit
claiming and would have misled the settle-up work.

Renames ScaffoldStubsTests to DomainMappingTests now that the entities it
guards are no longer stubs."
```

- [ ] **Step 8: Open the pull request**

```bash
git push
gh pr create --base main --title "Backend data model: Flyway schema, entities, per-unit claiming" --body "$(cat <<'EOF'
Implements the backend domain model per
[the design spec](docs/superpowers/specs/2026-08-20-backend-data-model-design.md).

## What changed

- Flyway owns the schema as versioned SQL; Hibernate drops to `ddl-auto: validate`
- `Bill` is the aggregate root, owning `Item` and `Participant` with cascade and orphan removal
- `ItemClaim` carries `unitIndex`, making each unit of a multi-quantity line independently claimable
- Repositories carry only finders with a near-term caller
- Money is stored as `long` cents throughout

## Notable decisions

**Per-unit claiming.** A line with `quantity > 1` exposes one claim slot per unit,
so three people can each take one of three tacos. The alternative — exploding the
line into separate `Item` rows — would force a rounding decision at parse time and
display identical items at different prices.

**Rounding stays a single pass.** Per-unit claiming introduces a second division.
Neither level rounds; only each participant's final total is floored, with the
leftover going to the payer. `ARCHITECTURE.md` is updated to match.

**One invariant is not in the database.** `unit_index < quantity` spans two tables,
so Postgres cannot express it as a CHECK. The claim endpoint will enforce it.

## Testing

`mvn test` — persistence tests cover cascade, orphan removal, all three unique
constraints, enum string storage, and every repository finder.

Reviewers: the local Postgres volume must be reset once, since it predates Flyway:
`docker compose down -v && docker compose up -d`
EOF
)"
```

---

## Self-Review

**Spec coverage** — every spec section maps to a task:

| Spec section | Task |
|---|---|
| Flyway migrations, not `ddl-auto: update` | 1 |
| Money as integer cents | 2 (Bill), 3 (Item) |
| `Bill` is the aggregate root | 3, 4 |
| Claiming is per unit | 5 |
| Room code format (column only) | 1, 2 |
| Schema DDL | 1 |
| Field semantics (`price_cents`, quantity, `DRAFT`) | 3, 2 |
| Rounding single-pass | 6 (documented; implemented by the settle-up task) |
| `unit_index < quantity` invariant | 5 (documented on the entity; enforced by the claim task) |
| Unclaimed reporting granularity | Out of scope — settle-up task |
| Entities | 2, 3, 4, 5 |
| Repositories | 2, 3, 4, 5 |
| Testing | 1–5 |
| Files added/changed | 1–6 |
| ARCHITECTURE.md corrections | 6 |
| Execution hazard (volume reset) | 1, Step 1 |

**Type consistency** — checked across tasks: `Bill.addItem`/`removeItem`/`addParticipant`, `Item(String, long, int)`, `Participant(String, String, Instant)`, `ItemClaim(Item, Participant, int, Instant)`, `findByItemBillId`, `findByItemIdAndParticipantIdAndUnitIndex`, `findByBillIdAndSessionToken`, `findByRoomCode`, `existsByRoomCode`. Every name used in a later task is defined in an earlier one.

**Known deferrals** (deliberate, recorded in the spec, not gaps): the room-code generator, expiry cleanup, the `unit_index < quantity` upper-bound check, and the settle-up calculation.
