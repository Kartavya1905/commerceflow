-- payment-sandbox schema. Its own database, so charges survive a payment-service crash: that is
-- what makes "charged, then the acknowledgement was lost" a real scenario rather than a fake one.

create table charges (
    id               text        primary key check (id ~ '^ch_[A-Za-z0-9]{8,64}$'),
    -- The provider's guarantee: one charge per key, however many times it is sent.
    idempotency_key  text        not null unique,
    amount_minor     bigint      not null check (amount_minor > 0),
    currency         text        not null check (currency ~ '^[A-Z]{3}$'),
    payment_token    text        not null,
    status           text        not null check (status in ('SUCCEEDED', 'DECLINED')),
    decline_code     text        check (decline_code in ('card_declined', 'insufficient_funds')),
    refunded_minor   bigint      not null default 0,
    created_at       timestamptz not null,
    constraint charges_refund_ck check (refunded_minor between 0 and amount_minor),
    constraint charges_decline_ck check ((status = 'DECLINED') = (decline_code is not null))
);

create table refunds (
    id              text        primary key check (id ~ '^re_[A-Za-z0-9]{8,64}$'),
    idempotency_key text        not null unique,
    charge_id       text        not null references charges (id),
    amount_minor    bigint      not null check (amount_minor > 0),
    created_at      timestamptz not null
);

create index refunds_charge_idx on refunds (charge_id);
