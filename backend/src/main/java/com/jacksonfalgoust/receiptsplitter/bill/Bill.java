package com.jacksonfalgoust.receiptsplitter.bill;

import com.jacksonfalgoust.receiptsplitter.item.Item;
import com.jacksonfalgoust.receiptsplitter.participant.Participant;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "bill", uniqueConstraints = @UniqueConstraint(columnNames = "room_code"))
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_code", nullable = false, length = 6)
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

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Item> items = new ArrayList<>();

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Participant> participants = new ArrayList<>();

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

    /** Read-only view — mutate through {@link #addItem} / {@link #removeItem} so the owning side stays consistent. */
    public List<Item> getItems() {
        return Collections.unmodifiableList(items);
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

    /** Read-only view — mutate through {@link #addParticipant} / {@link #removeParticipant} so the owning side stays consistent. */
    public List<Participant> getParticipants() {
        return Collections.unmodifiableList(participants);
    }

    /** Adds a participant and sets the owning side so both halves stay consistent. */
    public void addParticipant(Participant participant) {
        participants.add(participant);
        participant.setBill(this);
    }

    public void removeParticipant(Participant participant) {
        participants.remove(participant);
        participant.setBill(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Bill other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        // Constant so an entity's hash does not change when it goes from
        // transient to persistent, keeping it findable in a HashSet.
        return getClass().hashCode();
    }
}
