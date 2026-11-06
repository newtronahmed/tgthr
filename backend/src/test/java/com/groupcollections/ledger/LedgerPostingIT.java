package com.groupcollections.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class LedgerPostingIT extends ApiIntegrationTest {

  @Test
  @Criterion("INV-LEDGER-001")
  void balancedPostingSetsCommit() {
    UUID postingSet = UUID.randomUUID();

    insertBalanced(postingSet, 1_000);

    Integer entries =
        jdbc.queryForObject(
            "select count(*) from ledger_entries where posting_set_id = ?",
            Integer.class,
            postingSet);
    assertThat(entries).isEqualTo(2);
  }

  @Test
  @Criterion("INV-LEDGER-001")
  void unbalancedPostingSetsAreRejected() {
    UUID postingSet = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into ledger_entries
                        (posting_set_id, account, entry_type, direction, amount_pesewas)
                    values (?, 'PARTNER_HELD', 'CONTRIBUTION', 'DEBIT', 100)
                    """,
                    postingSet))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Criterion("INV-LEDGER-002")
  void ledgerEntriesCannotBeUpdatedOrDeleted() {
    UUID postingSet = UUID.randomUUID();
    insertBalanced(postingSet, 1_000);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update ledger_entries set amount_pesewas = 1 where posting_set_id = ?",
                    postingSet))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () -> jdbc.update("delete from ledger_entries where posting_set_id = ?", postingSet))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Criterion("INV-LEDGER-004")
  void aPostingSetRowAppearsAtMostOnce() {
    UUID postingSet = UUID.randomUUID();
    insertBalanced(postingSet, 1_000);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into ledger_entries
                        (posting_set_id, account, entry_type, direction, amount_pesewas)
                    values (?, 'GOAL_POOL', 'CONTRIBUTION', 'CREDIT', 1_000)
                    """,
                    postingSet))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Criterion("INV-LEDGER-006")
  void balancesAreDerivedFromEntriesAndNeverStored() {
    Integer balanceColumns =
        jdbc.queryForObject(
            """
            select count(*) from information_schema.columns
            where table_schema = 'public' and lower(column_name) like '%balance%'
            """,
            Integer.class);
    assertThat(balanceColumns).isZero();

    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    insertBalanced(first, 700);
    insertBalanced(second, 300);

    Long goalPoolBalance =
        jdbc.queryForObject(
            """
            select coalesce(sum(case when direction = 'CREDIT' then amount_pesewas
                                     else -amount_pesewas end), 0)
            from ledger_entries
            where account = 'GOAL_POOL' and posting_set_id in (?, ?)
            """,
            Long.class,
            first,
            second);
    assertThat(goalPoolBalance).isEqualTo(1_000);
  }

  private void insertBalanced(UUID postingSet, long amountPesewas) {
    jdbc.update(
        """
        insert into ledger_entries
            (posting_set_id, account, entry_type, direction, amount_pesewas)
        values (?, 'PARTNER_HELD', 'CONTRIBUTION', 'DEBIT', ?),
               (?, 'GOAL_POOL', 'CONTRIBUTION', 'CREDIT', ?)
        """,
        postingSet,
        amountPesewas,
        postingSet,
        amountPesewas);
  }
}
