-- Outbox, aggregate versions and inbox: a copy of libs/messaging's reference DDL
-- (src/main/resources/db/messaging/outbox-and-inbox.sql). Each service owns its schema, so each
-- applies it in its own migration.

create table outbox_events (
    id               uuid        primary key,
    seq              bigint      generated always as identity unique,
    topic            text        not null,
    partition_key    text        not null,
    event_type       text        not null,
    envelope         text        not null,
    headers          jsonb       not null default '{}',
    created_at       timestamptz not null,
    published_at     timestamptz,
    publish_attempts integer     not null default 0,
    last_error       text
);

create index outbox_unpublished_idx on outbox_events (seq) where published_at is null;
create index outbox_published_at_idx on outbox_events (published_at) where published_at is not null;

create table aggregate_versions (
    aggregate_type text   not null,
    aggregate_id   uuid   not null,
    version        bigint not null,
    primary key (aggregate_type, aggregate_id)
);

create table processed_events (
    consumer     text        not null,
    event_id     uuid        not null,
    processed_at timestamptz not null,
    primary key (consumer, event_id)
);

create index processed_events_at_idx on processed_events (processed_at);
