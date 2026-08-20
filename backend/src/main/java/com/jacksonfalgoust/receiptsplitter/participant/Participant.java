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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Someone splitting a bill. There are no accounts: {@code sessionToken} is a
 * random id the client stores, and it is what re-identifies the same person
 * after a refresh or a dropped WebSocket rather than a login.
 */
@Entity
@Table(name = "participant", uniqueConstraints = @UniqueConstraint(
        columnNames = {"bill_id", "session_token"}))
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
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
