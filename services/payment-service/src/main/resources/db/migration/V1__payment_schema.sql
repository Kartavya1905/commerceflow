-- payment-service schema, version 1. No card data is ever stored: payment_token_ref is the opaque
-- provider token the customer's client obtained (INV-8).

create table payment_attempts (
    -- Created once, before the provider is called, and sent as the provider's Idempotency-Key:
    -- however often the call is retried, the provider can charge this attempt only once.
    id                 uuid        primary key,
    order_id           uuid        not null,
    -- Null on a cancellation tombstone: an attempt that was cancelled before it was ever requested.
    customer_id        uuid,
    amount_minor       bigint      check (amount_minor > 0),
    currency           text        check (currency ~ '^[A-Z]{3}$'),
    payment_token_ref  text        check (payment_token_ref ~ '^tok_[a-z0-9_]{1,64}$'),
    status             text        not null
        check (status in ('INITIATED', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED',
                          'REFUND_PENDING', 'REFUNDED')),
    provider_charge_id text        check (provider_charge_id ~ '^ch_[A-Za-z0-9]{8,64}$'),
    provider_refund_id text        check (provider_refund_id ~ '^re_[A-Za-z0-9]{8,64}$'),
    failure_code       text,
    reconcile_attempts integer     not null default 0,
    next_reconcile_at  timestamptz,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    -- INV-3: one attempt per order, so at most one charge per order. A late or duplicated
    -- AuthorizePayment meets the existing row instead of creating a second attempt.
    constraint payment_attempts_order_uk unique (order_id),
    constraint payment_attempts_request_ck check (
        (customer_id is null and amount_minor is null and currency is null
            and payment_token_ref is null and status = 'CANCELLED')
        or (customer_id is not null and amount_minor is not null and currency is not null
            and payment_token_ref is not null)),
    -- Money was taken exactly when there is a charge id.
    constraint payment_attempts_charge_ck check (
        (status in ('SUCCEEDED', 'REFUND_PENDING', 'REFUNDED')) = (provider_charge_id is not null)),
    constraint payment_attempts_refund_ck check (
        (status = 'REFUNDED') = (provider_refund_id is not null))
);

create index payment_attempts_status_idx on payment_attempts (status, updated_at);
