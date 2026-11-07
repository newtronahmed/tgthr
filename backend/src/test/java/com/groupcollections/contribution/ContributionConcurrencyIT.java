package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ContributionConcurrencyIT extends ApiIntegrationTest {

  @Test
  @Criterion("AC-CONTRIB-015")
  void concurrentCreatesWithTheSameKeyCreateOneContribution() throws Exception {
    String slug = insertGoal("concurrent-idempotency", "OPEN", null);
    String key = "race-" + UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<ResponseEntity<Map<String, Object>>> call =
          () -> {
            start.await();
            return createContribution(slug, 5_000, "0240000001", key);
          };
      Future<ResponseEntity<Map<String, Object>>> first = pool.submit(call);
      Future<ResponseEntity<Map<String, Object>>> second = pool.submit(call);
      start.countDown();

      ResponseEntity<Map<String, Object>> firstResponse = first.get();
      ResponseEntity<Map<String, Object>> secondResponse = second.get();

      assertThat(firstResponse.getStatusCode().is2xxSuccessful()).isTrue();
      assertThat(secondResponse.getStatusCode().is2xxSuccessful()).isTrue();
      assertThat(firstResponse.getBody()).isNotNull();
      assertThat(secondResponse.getBody()).isNotNull();
      assertThat(secondResponse.getBody().get("id")).isEqualTo(firstResponse.getBody().get("id"));
      assertThat(PAYSTACK.promptCount()).isEqualTo(1);

      Integer stored =
          jdbc.queryForObject(
              "select count(*) from contributions where goal_id = (select id from goals where slug = ?)",
              Integer.class,
              slug);
      assertThat(stored).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @Criterion("AC-CONTRIB-004")
  void concurrentDuplicateSuccessCallbacksPostOnce() throws Exception {
    String slug = insertGoal("concurrent-callbacks", "OPEN", null);
    Map<String, Object> created = createContribution(slug, 5_000, "0240000001").getBody();
    assertThat(created).isNotNull();
    String id = (String) created.get("id");
    long total = ((Number) created.get("totalPesewas")).longValue();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> call =
          () -> {
            start.await();
            return PAYSTACK.sendSuccessCallback(webhookUrl(), id, total);
          };
      Future<Integer> first = pool.submit(call);
      Future<Integer> second = pool.submit(call);
      start.countDown();

      assertThat(first.get()).isEqualTo(204);
      assertThat(second.get()).isEqualTo(204);

      UUID contributionId = UUID.fromString(id);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from ledger_entries where contribution_id = ?",
                  Integer.class,
                  contributionId))
          .isEqualTo(4);
      assertThat(
              jdbc.queryForObject(
                  "select count(distinct posting_set_id) from ledger_entries where contribution_id = ?",
                  Integer.class,
                  contributionId))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
