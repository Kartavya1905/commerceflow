-- catalog-service schema, version 1.

create table products (
    id            uuid        primary key,
    sku           text        not null,
    name          text        not null,
    description   text        not null default '',
    category      text        not null,
    price_minor   bigint      not null check (price_minor >= 0),
    -- text plus a format check rather than char(3), which pads with spaces in Postgres.
    currency      text        not null check (currency ~ '^[A-Z]{3}$'),
    status        text        not null default 'ACTIVE' check (status in ('ACTIVE', 'ARCHIVED')),
    -- Maintained by Postgres on every insert and update; the application never writes it.
    -- Weights: a match in the name counts most, then category, then description.
    search_vector tsvector    generated always as (
                      setweight(to_tsvector('english', name), 'A')
                   || setweight(to_tsvector('english', category), 'B')
                   || setweight(to_tsvector('english', description), 'C')
                  ) stored,
    version       bigint      not null default 0,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    constraint products_sku_uk          unique (sku),
    constraint products_sku_format_ck   check (sku ~ '^[A-Z0-9-]{3,40}$'),
    constraint products_name_ck         check (char_length(name) between 1 and 200),
    constraint products_category_ck     check (char_length(category) between 1 and 60),
    constraint products_description_ck  check (char_length(description) <= 4000)
);

create index products_search_idx           on products using gin (search_vector);
create index products_active_category_idx  on products (category, created_at desc) where status = 'ACTIVE';
create index products_active_newest_idx    on products (created_at desc)           where status = 'ACTIVE';
create index products_active_price_idx     on products (price_minor)               where status = 'ACTIVE';

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
