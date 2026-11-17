insert into goals
    (id, slug, title, template, target_type, target_amount_minor_units, minimum_amount_minor_units,
     destination_type, second_approver, hold_window_minutes, surplus_rule, status)
values
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'e2e-maldives', 'E2E Maldives', 'TRIP', 'OPEN',
     500000, 1000, 'BENEFICIARY_MOMO', 'OFF', 60, 'PAY_BENEFICIARY', 'OPEN')
on conflict (slug) do nothing;

insert into users (id, phone_ciphertext, phone_hash)
values (
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    decode('00', 'hex'),
    decode(repeat('00', 32), 'hex')
)
on conflict (id) do nothing;

insert into payouts
    (id, goal_id, requested_by, amount_minor_units, fee_minor_units, net_minor_units,
     state, idempotency_key, hold_expires_at, created_at)
values (
    'cccccccc-cccc-cccc-cccc-cccccccccccc',
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    5000, 100, 4900, 'HOLD', 'e2e-payout-hold', now() + interval '1 hour', now()
)
on conflict (goal_id, idempotency_key) do nothing;

-- Operator E2E (T442). The bootstrap admin exists after startup; grant the roles the console uses.
insert into operator_roles (operator_id, role, granted_at)
select o.id, r.role, now()
from operators o
cross join (values ('REFUND_OPERATOR'), ('RECONCILER'), ('FINANCE_APPROVER')) as r(role)
where o.username = 'e2e.admin'
on conflict do nothing;

insert into goals
    (id, slug, title, template, target_type, target_amount_minor_units,
     minimum_amount_minor_units, destination_type, second_approver, hold_window_minutes,
     surplus_rule, status)
values
    ('d1000000-0000-0000-0000-000000000001', 'e2e-refund', 'E2E Refund', 'TRIP', 'OPEN',
     500000, 1000, 'BENEFICIARY_MOMO', 'OFF', 60, 'PAY_BENEFICIARY', 'OPEN'),
    ('e1000000-0000-0000-0000-000000000001', 'e2e-suspense', 'E2E Suspense', 'TRIP', 'OPEN',
     500000, 1000, 'BENEFICIARY_MOMO', 'OFF', 60, 'PAY_BENEFICIARY', 'CLOSED')
on conflict (slug) do nothing;

insert into contributions
    (id, goal_id, amount_minor_units, processing_fee_minor_units, platform_fee_minor_units,
     total_minor_units, currency_code, state, provider_reference, payer_phone_ciphertext,
     payer_phone_hash, display_name, anonymous, idempotency_key, requires_reconciliation,
     created_at, updated_at, confirmed_at)
values
    ('d0000000-0000-0000-0000-000000000001', 'd1000000-0000-0000-0000-000000000001',
     10000, 200, 150, 10350, 'GHS', 'CONFIRMED', 'e2e-refund-contribution',
     decode('00', 'hex'), decode(repeat('00', 32), 'hex'), 'E2E Refund Payer', false,
     'e2e-refund-contribution', false, now(), now(), now()),
    ('e0000000-0000-0000-0000-000000000001', 'e1000000-0000-0000-0000-000000000001',
     5000, 100, 75, 5175, 'GHS', 'CONFIRMED', 'e2e-suspense-contribution',
     decode('00', 'hex'), decode(repeat('00', 32), 'hex'), 'E2E Suspense Payer', false,
     'e2e-suspense-contribution', true, now(), now(), now())
on conflict (id) do nothing;

-- Confirmation posting for the refundable contribution.
insert into ledger_entries
    (posting_set_id, contribution_id, account, entry_type, direction, amount_minor_units,
     currency_code)
values
    ('d2000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001',
     'PARTNER_HELD', 'CONTRIBUTION', 'DEBIT', 10350, 'GHS'),
    ('d2000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001',
     'GOAL_POOL', 'CONTRIBUTION', 'CREDIT', 10000, 'GHS'),
    ('d2000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001',
     'FEE', 'FEE', 'CREDIT', 200, 'GHS'),
    ('d2000000-0000-0000-0000-000000000001', 'd0000000-0000-0000-0000-000000000001',
     'PLATFORM_FEE', 'PLATFORM_FEE', 'CREDIT', 150, 'GHS')
on conflict do nothing;

-- Suspense posting for the reconciliation item.
insert into ledger_entries
    (posting_set_id, contribution_id, account, entry_type, direction, amount_minor_units,
     currency_code)
values
    ('e2000000-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-000000000001',
     'PARTNER_HELD', 'SUSPENSE_HOLD', 'DEBIT', 5175, 'GHS'),
    ('e2000000-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-000000000001',
     'SUSPENSE', 'SUSPENSE_HOLD', 'CREDIT', 5000, 'GHS'),
    ('e2000000-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-000000000001',
     'FEE', 'FEE', 'CREDIT', 100, 'GHS'),
    ('e2000000-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-000000000001',
     'PLATFORM_FEE', 'PLATFORM_FEE', 'CREDIT', 75, 'GHS')
on conflict do nothing;
