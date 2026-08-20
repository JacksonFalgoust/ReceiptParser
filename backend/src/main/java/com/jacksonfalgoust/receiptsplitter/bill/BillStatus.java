package com.jacksonfalgoust.receiptsplitter.bill;

/**
 * Lifecycle of a bill.
 *
 * <p>{@code DRAFT} is currently unreachable: a parsed receipt is returned to
 * the client for editing without being persisted, so every row that reaches
 * the database starts at {@code OPEN}. The value is retained for a future
 * server-side draft flow.
 */
public enum BillStatus {
    DRAFT,
    OPEN,
    CLOSED
}
