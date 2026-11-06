package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.support.ApiIntegrationTest;
import com.groupcollections.support.Criterion;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;

class ContributionIdempotencyRaceIT extends ApiIntegrationTest {

  static final CyclicBarrier BARRIER = new CyclicBarrier(2);

  @TestConfiguration
  static class RaceIds {
    @Bean
    @Primary
    IdGenerator raceIdGenerator() {
      return new IdGenerator() {
        @Override
        public UUID next() {
          try {
            BARRIER.await(5, TimeUnit.SECONDS);
          } catch (Exception failure) {
            throw new IllegalStateException("race barrier failed", failure);
          }
          return super.next();
        }
      };
    }
  }

  @Test
  @Criterion("AC-CONTRIB-015")
  void bothRequestsPassThePreCheckAndTheDatabasePicksOne() throws Exception {
    String slug = insertGoal("race-idempotency", "OPEN", null);
    String key = "race-" + UUID.randomUUID();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<ResponseEntity<Map<String, Object>>> call =
          () -> createContribution(slug, 5_000, "0240000001", key);
      Future<ResponseEntity<Map<String, Object>>> first = pool.submit(call);
      Future<ResponseEntity<Map<String, Object>>> second = pool.submit(call);

      ResponseEntity<Map<String, Object>> firstResponse = first.get();
      ResponseEntity<Map<String, Object>> secondResponse = second.get();

      assertThat(firstResponse.getBody()).isNotNull();
      assertThat(secondResponse.getBody()).isNotNull();
      assertThat(secondResponse.getBody().get("id")).isEqualTo(firstResponse.getBody().get("id"));
      assertThat(PAYSTACK.promptCount()).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from contributions where goal_id = (select id from goals where slug = ?)",
                  Integer.class,
                  slug))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
