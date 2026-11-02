-- The checkout saga (ARCHITECTURE.md section E.2): one row per order, changed only by
-- SagaOrchestrator, in the same transaction as the order's status and the next outgoing message.

create table saga_instances (
    id                 uuid        primary key,
    order_id           uuid        not null unique references orders (id) on delete cascade,
    state              text        not null
        check (state in ('AWAITING_RESERVATION', 'AWAITING_PAYMENT', 'AWAITING_RECONCILIATION',
                         'CANCELLING_PAYMENT', 'AWAITING_COMMIT', 'REFUNDING_PAYMENT',
                         'RELEASING_INVENTORY', 'COMPLETED', 'FAILED')),
    -- Decided when compensation starts; it is what the order fails with once stock is released.
    failure_reason     text
        check (failure_reason in ('OUT_OF_STOCK', 'PAYMENT_DECLINED', 'PAYMENT_TIMEOUT',
                                  'PRICE_UNAVAILABLE', 'INTERNAL_ERROR')),
    payment_attempt_id uuid,
    -- Re-sends of the current step's command; the deadline sweeper (Milestone 7) uses both.
    step_attempts      integer     not null default 0,
    deadline_at        timestamptz,
    version            bigint      not null default 0,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    constraint saga_instances_deadline_ck
        check ((state in ('COMPLETED', 'FAILED')) = (deadline_at is null)),
    constraint saga_instances_failed_ck check (state <> 'FAILED' or failure_reason is not null),
    constraint saga_instances_completed_ck
        check (state <> 'COMPLETED' or payment_attempt_id is not null)
);

create index saga_instances_deadline_idx on saga_instances (deadline_at)
    where deadline_at is not null;

-- Orders placed before the saga existed get the saga state matching their status. One that had
-- reached PAYMENT_PROCESSING never had payment requested, so its deadline is already due: the
-- sweeper re-sends AuthorizePayment for it. No order could be CONFIRMED before the saga; if one
-- were, the missing case leaves state null and the migration fails rather than guess.
insert into saga_instances (id, order_id, state, failure_reason, deadline_at, created_at,
                            updated_at)
select gen_random_uuid(),
       o.id,
       case o.status
           when 'PENDING' then 'AWAITING_RESERVATION'
           when 'PAYMENT_PROCESSING' then 'AWAITING_PAYMENT'
           when 'FAILED' then 'FAILED'
       end,
       o.failure_reason,
       case when o.status in ('PENDING', 'PAYMENT_PROCESSING') then now() end,
       o.created_at,
       now()
  from orders o;
