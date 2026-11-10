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
