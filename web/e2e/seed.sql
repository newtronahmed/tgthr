insert into goals
    (id, slug, title, template, target_type, target_amount_pesewas, minimum_amount_pesewas,
     destination_type, second_approver, hold_window_minutes, surplus_rule, status)
values
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'e2e-maldives', 'E2E Maldives', 'TRIP', 'OPEN',
     500000, 1000, 'BENEFICIARY_MOMO', 'OFF', 60, 'PAY_BENEFICIARY', 'OPEN')
on conflict (slug) do nothing;
