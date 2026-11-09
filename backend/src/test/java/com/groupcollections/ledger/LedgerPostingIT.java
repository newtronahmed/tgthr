package com.groupcollections.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class LedgerPostingIT extends ApiIntegrationTest {

  @Autowired private LedgerRepository ledger;

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
                        (posting_set_id, account, entry_type, direction, amount_minor_units)
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
                    "update ledger_entries set amount_minor_units = 1 where posting_set_id = ?",
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
                        (posting_set_id, account, entry_type, direction, amount_minor_units)
                    values (?, 'GOAL_POOL', 'CONTRIBUTION', 'CREDIT', 1_000)
                    """,
                    postingSet))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @Criterion("INV-LEDGER-002")
  void appendOnlyTablesCannotBeTruncated() {
    assertThatThrownBy(() -> jdbc.execute("truncate table ledger_entries"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.execute("truncate table contribution_events"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void financialColumnsUseMinorUnitsNames() {
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from information_schema.columns
                where table_schema = 'public' and column_name like '%pesewas%'
                """,
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from information_schema.columns
                where table_schema = 'public'
                  and column_name in ('amount_minor_units', 'fixed_amount_minor_units',
                                      'minimum_amount_minor_units', 'total_minor_units')
                """,
                Integer.class))
        .isGreaterThanOrEqualTo(4);
  }

  @Test
  @Criterion("INV-LEDGER-007")
  void ledgerRepositoryWritesTheEntryCurrency() {
    String slug = insertGoal("ledger-currency", "OPEN", null);
    Map<String, Object> contribution = createContribution(slug, 5_000, "0240000001").getBody();
    assertThat(contribution).isNotNull();
    UUID contributionId = UUID.fromString((String) contribution.get("id"));
    UUID postingSet = UUID.randomUUID();
    ledger.post(
        contributionId,
        new PostingSet(
            postingSet,
            List.of(
                new LedgerEntry(
                    LedgerAccount.PARTNER_HELD,
                    LedgerEntryType.CONTRIBUTION,
                    LedgerDirection.DEBIT,
                    Money.of(100, "NGN")),
                new LedgerEntry(
                    LedgerAccount.GOAL_POOL,
                    LedgerEntryType.CONTRIBUTION,
                    LedgerDirection.CREDIT,
                    Money.of(100, "NGN")))));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entries where posting_set_id = ? and currency_code = 'NGN'",
                Integer.class,
                postingSet))
        .isEqualTo(2);
  }

  @Test
  @Criterion("INV-LEDGER-007")
  void everyPostingRecordsCurrencyAndRejectsMixedCurrencySets() {
    UUID postingSet = UUID.randomUUID();
    insertBalanced(postingSet, 1_000);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ledger_entries where posting_set_id = ? and currency_code = 'GHS'",
                Integer.class,
                postingSet))
        .isEqualTo(2);

    UUID mixed = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into ledger_entries
                        (posting_set_id, account, entry_type, direction, amount_minor_units,
                         currency_code)
                    values (?, 'PARTNER_HELD', 'CONTRIBUTION', 'DEBIT', 100, 'GHS'),
                           (?, 'GOAL_POOL', 'CONTRIBUTION', 'CREDIT', 100, 'NGN')
                    """,
                    mixed,
                    mixed))
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
            select coalesce(sum(case when direction = 'CREDIT' then amount_minor_units
                                     else -amount_minor_units end), 0)
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
            (posting_set_id, account, entry_type, direction, amount_minor_units)
        values (?, 'PARTNER_HELD', 'CONTRIBUTION', 'DEBIT', ?),
               (?, 'GOAL_POOL', 'CONTRIBUTION', 'CREDIT', ?)
        """,
        postingSet,
        amountPesewas,
        postingSet,
        amountPesewas);
  }
}
