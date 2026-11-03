-- Admin actions on payments are audited like every other service's (ARCHITECTURE.md D.4): who did
-- what to which attempt, and the outcome. Same shape as the other services' audit_events.

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
