CREATE TABLE IF NOT EXISTS collection_order (
                                                id              VARCHAR(30)   PRIMARY KEY,
    merchant_id     VARCHAR(30)   NOT NULL,
    status          VARCHAR(20)   NOT NULL CHECK (status IN ('OPEN', 'BLOCKED')),
    pending_balance NUMERIC(19,2) NOT NULL CHECK (pending_balance >= 0)
    );

CREATE TABLE IF NOT EXISTS payment_report (
    id                  BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_reference   VARCHAR(50)   NOT NULL UNIQUE,
    -- Sin FK: se persisten rechazos de órdenes inexistentes (RF03)
    collection_order_id VARCHAR(30)   NOT NULL,
    merchant_id         VARCHAR(30)   NOT NULL,
    -- amount <= 0 se permite: se guarda como REJECTED / INVALID_AMOUNT
    amount              NUMERIC(19,2) NOT NULL,
    status              VARCHAR(20)   NOT NULL CHECK (status IN ('APPLIED', 'REJECTED')),
    rejection_reason    VARCHAR(40)   CHECK (rejection_reason IN (
                                             'INVALID_AMOUNT', 'ORDER_NOT_FOUND', 'MERCHANT_MISMATCH',
                                             'ORDER_NOT_ENABLED', 'INSUFFICIENT_BALANCE')),
    payload_hash        CHAR(64)      NOT NULL,
    processed_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT chk_rejection_consistency CHECK (
(status = 'APPLIED'  AND rejection_reason IS NULL) OR
(status = 'REJECTED' AND rejection_reason IS NOT NULL)
    ),
    CONSTRAINT chk_applied_amount_positive CHECK (status <> 'APPLIED' OR amount > 0)
    );

CREATE INDEX IF NOT EXISTS idx_payment_report_recent
    ON payment_report (processed_at DESC, id DESC);