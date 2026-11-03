-- The reason sent with the current step's compensating command (ReleaseInventory, CancelPayment),
-- so the deadline sweeper re-sends exactly what was sent the first time. Null for steps whose
-- command has no reason, and for sagas saved before this column existed.

alter table saga_instances
    add column command_reason text
        check (command_reason in ('PAYMENT_FAILED', 'RESERVATION_TIMEOUT', 'PAYMENT_TIMEOUT',
                                  'COMMIT_FAILED', 'SAGA_TIMEOUT'));

-- The sweeper's two questions: what is overdue, and how many sagas are past their last re-send.
create index saga_instances_stuck_idx on saga_instances (step_attempts)
    where deadline_at is not null;
