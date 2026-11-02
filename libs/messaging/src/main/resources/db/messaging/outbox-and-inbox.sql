-- Tables every service that publishes or consumes events needs. Services copy this into their
-- own Flyway migration (each service owns its schema); the messaging tests apply it directly.

-- Transactional outbox: rows are written in the same transaction as the business change and
-- sent to Kafka afterwards by OutboxPublisher. seq gives a total order; for one aggregate it is
-- also commit order, because aggregate_versions serialises writers of the same aggregate.
create table outbox_events (
    id               uuid        primary key,
    seq              bigint      generated always as identity unique,
    topic            text        not null,
    partition_key    text        not null,
    event_type       text        not null,
    -- The exact JSON that was validated against the contract and will be published.
    envelope         text        not null,
    headers          jsonb       not null default '{}',
    created_at       timestamptz not null,
    published_at     timestamptz,
    publish_attempts integer     not null default 0,
    last_error       text
);

create index outbox_unpublished_idx on outbox_events (seq) where published_at is null;
create index outbox_published_at_idx on outbox_events (published_at) where published_at is not null;

-- Durable per-aggregate event counter (the envelope's aggregateVersion). Outbox rows are cleaned
-- up, so the counter cannot be derived from them.
create table aggregate_versions (
    aggregate_type text   not null,
    aggregate_id   uuid   not null,
    version        bigint not null,
    primary key (aggregate_type, aggregate_id)
);

-- Inbox: one row per event a consumer has applied, written in the same transaction as the
-- effect, so a redelivered event is recognised and skipped.
create table processed_events (
    consumer     text        not null,
    event_id     uuid        not null,
    processed_at timestamptz not null,
    primary key (consumer, event_id)
);

create index processed_events_at_idx on processed_events (processed_at);
