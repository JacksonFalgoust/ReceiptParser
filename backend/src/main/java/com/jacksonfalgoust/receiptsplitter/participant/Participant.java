package com.jacksonfalgoust.receiptsplitter.participant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Participant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Fields (billId, name, sessionToken) and accessors are added when the
    // join-a-bill implementation work begins.
}
