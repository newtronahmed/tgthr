package com.groupcollections.ledger;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persists posting sets. The database enforces balance, idempotency and append-only. */
@Repository
public class LedgerRepository {

  private final JdbcTemplate jdbc;

  public LedgerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void post(UUID contributionId, PostingSet postingSet) {
    for (LedgerEntry entry : postingSet.entries()) {
      jdbc.update(
          """
          insert into ledger_entries
              (posting_set_id, contribution_id, account, entry_type, direction, amount_minor_units,
               currency_code)
          values (?, ?, ?, ?, ?, ?, ?)
          """,
          postingSet.id(),
          contributionId,
          entry.account().name(),
          entry.type().name(),
          entry.direction().name(),
          entry.amount().minorUnits(),
          entry.amount().currencyCode());
    }
  }
}
