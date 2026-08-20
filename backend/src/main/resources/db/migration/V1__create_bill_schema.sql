CREATE TABLE bill (
    id             BIGSERIAL PRIMARY KEY,
    room_code      VARCHAR(6)   NOT NULL UNIQUE,
    payer_name     VARCHAR(100) NOT NULL,
    subtotal_cents BIGINT       NOT NULL DEFAULT 0,
    tax_cents      BIGINT       NOT NULL DEFAULT 0,
    tip_cents      BIGINT       NOT NULL DEFAULT 0,
    total_cents    BIGINT       NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL
                   CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED')),
    created_at     TIMESTAMPTZ  NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL
);

CREATE TABLE item (
    id          BIGSERIAL PRIMARY KEY,
    bill_id     BIGINT       NOT NULL REFERENCES bill (id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    price_cents BIGINT       NOT NULL,
    quantity    INTEGER      NOT NULL DEFAULT 1 CHECK (quantity > 0)
);

CREATE INDEX idx_item_bill_id ON item (bill_id);

CREATE TABLE participant (
    id            BIGSERIAL PRIMARY KEY,
    bill_id       BIGINT       NOT NULL REFERENCES bill (id) ON DELETE CASCADE,
    name          VARCHAR(100) NOT NULL,
    session_token VARCHAR(64)  NOT NULL,
    joined_at     TIMESTAMPTZ  NOT NULL,
    UNIQUE (bill_id, session_token)
);

CREATE INDEX idx_participant_bill_id ON participant (bill_id);

CREATE TABLE item_claim (
    id             BIGSERIAL PRIMARY KEY,
    item_id        BIGINT      NOT NULL REFERENCES item (id) ON DELETE CASCADE,
    participant_id BIGINT      NOT NULL
                   REFERENCES participant (id) ON DELETE CASCADE,
    unit_index     INTEGER     NOT NULL DEFAULT 0 CHECK (unit_index >= 0),
    claimed_at     TIMESTAMPTZ NOT NULL,
    UNIQUE (item_id, participant_id, unit_index)
);

CREATE INDEX idx_item_claim_item_id ON item_claim (item_id);
CREATE INDEX idx_item_claim_participant_id ON item_claim (participant_id);
