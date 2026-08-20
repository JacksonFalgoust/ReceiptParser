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
