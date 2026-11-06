# Backend

Spring Boot 3, Java 21, Gradle (Kotlin DSL), PostgreSQL, Flyway. One modular monolith (ADR 0001).

## Module layout

Each module is a direct sub-package of `com.groupcollections`. Spring Modulith verifies in `ModularityTests` that modules only reach each other through their public API.

| Package | Responsibility | Status |
| --- | --- | --- |
| `ledger` | Money type, postings, derived balances | Money and posting model |
| `contribution` | Fee calculator, contribution state machine, provider callbacks | Fee calculator only |
| `payments` | Partner client, signature checks, reconciliation | Not started |
| `goal` | Groups, goals, templates, settings | Not started |
| `identity` | Phone OTP, sessions, roles | Not started |
| `payout` | Payout requests, hold window, approvals | Not started |
| `notification` | Push and SMS channel selection | Not started |

## Commands

Requires JDK 21. Set `JAVA_HOME` to a JDK 21 installation before running Gradle; the build does not auto-download toolchains. The Gradle wrapper is committed. For local runs export `DATABASE_PASSWORD=collections` (the docker compose value); no credentials are committed.

```
./gradlew test           # unit and property tests
./gradlew spotlessApply  # format
./gradlew check          # tests, coverage gates, format check
```

## Testing notes

- Tag every test with `@Criterion("ID")` from `com.groupcollections.support`.
- Pending criteria are `@Disabled` tests. Enable one, see it fail, then implement.
- Integration tests use Testcontainers with PostgreSQL, so Docker must be running.
- Tests never call the real payment provider.
