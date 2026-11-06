# Migrations

Flyway migrations live here as `V<number>__<description>.sql`. They are forward-only and must be backward compatible with the version currently running (add first, remove in a later release).

The first migration (ledger tables, with UPDATE and DELETE rejected by database permissions) arrives together with its first failing integration test, as required by the constitution. Do not add a migration without a spec that needs it.

Production grants: the application role must hold only `SELECT` and `INSERT` on `ledger_entries` and `contribution_events`. Triggers also reject UPDATE, DELETE and TRUNCATE in development, but privileges remain the deployment control.
