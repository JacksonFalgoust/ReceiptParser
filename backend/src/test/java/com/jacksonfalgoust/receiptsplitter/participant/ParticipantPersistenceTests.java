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
