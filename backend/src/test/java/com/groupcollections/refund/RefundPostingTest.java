package com.groupcollections.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.ledger.LedgerAccount;
import com.groupcollections.ledger.LedgerDirection;
import com.groupcollections.ledger.PostingSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundPostingTest {

  @Test
  void reversalDebitsTheCreditedAccountsAndCreditsPartnerHeldGross() {
    Refund refund = refund(1_000, 15, 20);

    PostingSet posting = RefundPosting.reversal(refund);

    assertThat(posting.entries())
        .anySatisfy(
            entry -> {
              assertThat(entry.account()).isEqualTo(LedgerAccount.GOAL_POOL);
              assertThat(entry.direction()).isEqualTo(LedgerDirection.DEBIT);
              assertThat(entry.amount().minorUnits()).isEqualTo(1_000);
            })
        .anySatisfy(
            entry -> {
              assertThat(entry.account()).isEqualTo(LedgerAccount.PLATFORM_FEE);
              assertThat(entry.direction()).isEqualTo(LedgerDirection.DEBIT);
              assertThat(entry.amount().minorUnits()).isEqualTo(15);
            })
        .anySatisfy(
            entry -> {
              assertThat(entry.account()).isEqualTo(LedgerAccount.FEE);
              assertThat(entry.direction()).isEqualTo(LedgerDirection.DEBIT);
              assertThat(entry.amount().minorUnits()).isEqualTo(20);
            })
        .anySatisfy(
            entry -> {
              assertThat(entry.account()).isEqualTo(LedgerAccount.PARTNER_HELD);
              assertThat(entry.direction()).isEqualTo(LedgerDirection.CREDIT);
              assertThat(entry.amount().minorUnits()).isEqualTo(1_035);
            });
  }

  @Test
  void retainedFeePostsThePartnerHeldLossSet() {
    PostingSet loss = RefundPosting.loss(refund(1_000, 15, 20)).orElseThrow();

    assertThat(loss.entries())
        .anySatisfy(
            entry -> {
              assertThat(entry.account()).isEqualTo(LedgerAccount.PLATFORM_LOSS);
              assertThat(entry.direction()).isEqualTo(LedgerDirection.DEBIT);
              assertThat(entry.amount().minorUnits()).isEqualTo(20);
            })
        .anySatisfy(
            entry -> {
              assertThat(entry.account()).isEqualTo(LedgerAccount.PARTNER_HELD);
              assertThat(entry.direction()).isEqualTo(LedgerDirection.CREDIT);
              assertThat(entry.amount().minorUnits()).isEqualTo(20);
            });
  }

  @Test
  void zeroProcessingFeeHasNoLossSet() {
    assertThat(RefundPosting.loss(refund(1_000, 15, 0))).isEmpty();
  }

  @Test
  void suspenseRefundsDebitSuspense() {
    Refund refund = refundWithSource("SUSPENSE", 1_000, 0, 0);

    assertThat(RefundPosting.reversal(refund).entries())
        .anySatisfy(
            entry -> {
              assertThat(entry.account()).isEqualTo(LedgerAccount.SUSPENSE);
              assertThat(entry.direction()).isEqualTo(LedgerDirection.DEBIT);
            });
  }

  private static Refund refund(long amount, long platformFee, long processingFee) {
    return refundWithSource("GOAL_POOL", amount, platformFee, processingFee);
  }

  private static Refund refundWithSource(
      String source, long amount, long platformFee, long processingFee) {
    Instant now = Instant.now();
    return new Refund(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        source,
        amount,
        platformFee,
        processingFee,
        amount + platformFee + processingFee,
        "GHS",
        RefundState.PENDING,
        "idem",
        "txn",
        null,
        UUID.randomUUID(),
        "reason",
        null,
        null,
        null,
        null,
        now,
        now);
  }
}
