-- identity-service schema, version 1.
-- Emails are stored lower-cased (enforced below), so uniqueness is case-insensitive without
-- needing the citext extension.

create table users (
    id                 uuid        primary key,
    email              text        not null,
    password_hash      text        not null,
    display_name       text        not null,
    role               text        not null check (role in ('CUSTOMER', 'ADMIN')),
    status             text        not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    failed_login_count integer     not null default 0 check (failed_login_count >= 0),
    locked_until       timestamptz,
    version            bigint      not null default 0,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    constraint users_email_uk           unique (email),
    constraint users_email_lowercase_ck check (email = lower(email)),
    constraint users_email_length_ck    check (char_length(email) between 3 and 254),
    constraint users_display_name_ck    check (char_length(display_name) between 1 and 100)
);

create index users_role_idx on users (role);

-- Only a SHA-256 hash of each refresh token is stored. A family is one login session: every
-- rotation stays in the family, and reuse of an old token revokes the whole family.
create table refresh_tokens (
    id          uuid        primary key,
    user_id     uuid        not null references users (id) on delete cascade,
    token_hash  bytea       not null,
    family_id   uuid        not null,
    expires_at  timestamptz not null,
    revoked_at  timestamptz,
    replaced_by uuid,
    created_at  timestamptz not null,
    constraint refresh_tokens_hash_uk     unique (token_hash),
    constraint refresh_tokens_hash_len_ck check (octet_length(token_hash) = 32)
);

create index refresh_tokens_family_idx  on refresh_tokens (family_id);
create index refresh_tokens_user_idx    on refresh_tokens (user_id);
create index refresh_tokens_expires_idx on refresh_tokens (expires_at);

-- Written in the same transaction as the action it records. Never holds secrets.
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
