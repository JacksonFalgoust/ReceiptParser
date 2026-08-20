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
        assertThat(reloaded.getCreatedAt()).isEqualTo(bill.getCreatedAt());
        assertThat(reloaded.getExpiresAt()).isEqualTo(bill.getExpiresAt());
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
