package com.jacksonfalgoust.receiptsplitter.item;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * No custom finders: items are reached through the {@code Bill} aggregate.
 * This exists so the claim path can call {@code getReferenceById} to attach an
 * {@code ItemClaim} without loading the row.
 */
public interface ItemRepository extends JpaRepository<Item, Long> {
}
