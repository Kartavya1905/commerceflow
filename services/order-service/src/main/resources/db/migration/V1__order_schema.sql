-- order-service schema, version 1. The saga and outbox tables arrive in Milestones 5 and 6.

create table orders (
    id              uuid        primary key,
    customer_id     uuid        not null,
    status          text        not null
        check (status in ('PENDING', 'PAYMENT_PROCESSING', 'CONFIRMED', 'FAILED')),
    failure_reason  text
        check (failure_reason in ('OUT_OF_STOCK', 'PAYMENT_DECLINED', 'PAYMENT_TIMEOUT',
                                  'PRICE_UNAVAILABLE', 'INTERNAL_ERROR')),
    total_minor     bigint      not null check (total_minor >= 0),
    currency        text        not null check (currency ~ '^[A-Z]{3}$'),
    payment_token   text        not null check (payment_token ~ '^tok_[a-z0-9_]{1,64}$'),
    idempotency_key text        not null check (idempotency_key ~ '^[A-Za-z0-9-]{16,64}$'),
    -- SHA-256 of the canonical request, so a reused key with a different body is detected.
    request_hash    bytea       not null check (octet_length(request_hash) = 32),
    version         bigint      not null default 0,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    -- FR-08: one order per customer and key. The database, not the code, settles races.
    constraint orders_idempotency_uk unique (customer_id, idempotency_key),
    constraint orders_failure_reason_ck check ((status = 'FAILED') = (failure_reason is not null))
);

create index orders_customer_history_idx on orders (customer_id, created_at desc, id);
create index orders_status_created_idx   on orders (status, created_at desc);

-- Name and price are copied at order time: later catalog edits must not change past orders.
create table order_items (
    order_id         uuid    not null references orders (id) on delete cascade,
    product_id       uuid    not null,
    product_name     text    not null,
    unit_price_minor bigint  not null check (unit_price_minor >= 0),
    quantity         integer not null check (quantity between 1 and 1000),
    primary key (order_id, product_id)
);

create table order_status_history (
    id          bigint      generated always as identity primary key,
    order_id    uuid        not null references orders (id) on delete cascade,
    from_status text,
    to_status   text        not null,
    reason      text,
    at          timestamptz not null
);

create index order_status_history_order_idx on order_status_history (order_id, at);

create table audit_events (
    id             bigint      generated always as identity primary key,
    occurred_at    timestamptz not null default now(),
    actor_id       uuid,
    actor_role     text,
    action         text        not null,
    resource_type  text        not null,
    resource_id    text,
    outcome        text        not null check (outcome in ('SUCCESS', 'FAILURE', 'DENIED')),
    correlation_id text,
    details        jsonb       not null default '{}'
);

create index audit_events_occurred_at_idx on audit_events (occurred_at);
create index audit_events_actor_idx       on audit_events (actor_id, occurred_at);
