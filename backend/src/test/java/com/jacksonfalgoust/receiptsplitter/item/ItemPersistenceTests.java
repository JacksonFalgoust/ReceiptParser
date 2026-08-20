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
