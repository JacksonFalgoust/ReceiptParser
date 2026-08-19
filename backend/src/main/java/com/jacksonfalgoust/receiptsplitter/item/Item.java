package com.jacksonfalgoust.receiptsplitter.item;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Fields (billId, name, price, quantity) and accessors are added when
    // the receipt-parsing implementation work begins.
}
