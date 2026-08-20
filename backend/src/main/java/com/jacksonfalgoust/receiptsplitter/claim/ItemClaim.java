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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

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
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Item item;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
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
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
