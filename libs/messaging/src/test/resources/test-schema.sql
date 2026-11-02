-- What the test listener writes when it applies an event: one row per application.
create table applied_events (
    seq      bigint generated always as identity primary key,
    event_id uuid   not null,
    order_id uuid   not null
);
