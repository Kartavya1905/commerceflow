-- inventory-service schema, version 1.
--
-- Three layers stop overselling: rows are locked before a decision is made, every update carries
-- its own guard (available >= quantity), and the CHECK constraints below reject any write that
-- would make a count negative or break conservation (INV-1, INV-2).

create table inventory (
    product_id        uuid        primary key,
    available         integer     not null default 0 check (available >= 0),
    reserved          integer     not null default 0 check (reserved >= 0),
    committed         integer     not null default 0 check (committed >= 0),
    total_received    bigint      not null default 0 check (total_received >= 0),
    total_written_off bigint      not null default 0 check (total_written_off >= 0),
    version           bigint      not null default 0,
    updated_at        timestamptz not null,
    -- Stock is never created or destroyed except by an audited adjustment.
    constraint inventory_conservation_ck
        check (available + reserved + committed = total_received - total_written_off)
);

-- One row per order: the reservation decision. It exists before any stock moves, so a duplicate
-- or retried request finds the decision instead of making a second one. A RELEASED row with no
-- lines is a tombstone: a release that arrived before the reserve, which must then not take stock.
create table order_reservations (
    order_id             uuid        primary key,
    status               text        not null
        check (status in ('RESERVED', 'REJECTED', 'COMMITTED', 'RELEASED', 'EXPIRED')),
    reject_reason        text        check (reject_reason in ('OUT_OF_STOCK', 'UNKNOWN_PRODUCT')),
    rejected_product_ids jsonb,
    -- Long-stop only: the saga releases reservations; this catches ones it never resolves.
    expires_at           timestamptz,
    created_at           timestamptz not null,
    updated_at           timestamptz not null,
    constraint order_reservations_reason_ck check ((status = 'REJECTED') = (reject_reason is not null))
);

create index order_reservations_expiry_idx on order_reservations (expires_at) where status = 'RESERVED';

create table reservation_lines (
    order_id   uuid    not null references order_reservations (order_id) on delete cascade,
    product_id uuid    not null references inventory (product_id),
    quantity   integer not null check (quantity between 1 and 1000),
    primary key (order_id, product_id)
);

create index reservation_lines_product_idx on reservation_lines (product_id);

create table stock_adjustments (
    id         bigint      generated always as identity primary key,
    product_id uuid        not null references inventory (product_id),
    delta      integer     not null check (delta <> 0),
    reason     text        not null check (reason in ('RESTOCK', 'WRITE_OFF', 'CORRECTION')),
    note       text        check (char_length(note) <= 500),
    actor_id   uuid,
    at         timestamptz not null
);

create index stock_adjustments_product_idx on stock_adjustments (product_id, at desc);

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
