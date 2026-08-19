package com.jacksonfalgoust.receiptsplitter.claim;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class ItemClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Fields (itemId, participantId) and accessors are added when the
    // claim/unclaim implementation work begins. A shared item has multiple
    // rows; an exclusively-claimed item has one — no special-casing.
}
