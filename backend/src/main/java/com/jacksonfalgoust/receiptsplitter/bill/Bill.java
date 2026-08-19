package com.jacksonfalgoust.receiptsplitter.bill;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Fields (roomCode, payerName, subtotal, tax, tip, total, status,
    // createdAt, expiresAt) and accessors are added when the bill-creation
    // implementation work begins. See ARCHITECTURE.md.
}
