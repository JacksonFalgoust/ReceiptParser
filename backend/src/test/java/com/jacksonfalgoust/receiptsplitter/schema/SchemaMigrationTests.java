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
