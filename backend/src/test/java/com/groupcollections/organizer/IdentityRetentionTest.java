package com.groupcollections.organizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.identity.IdentityRetentionJob;
import com.groupcollections.support.ApiIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdentityRetentionTest extends ApiIntegrationTest {

  @Autowired private IdentityRetentionJob retention;

  @Test
  void oldChallengesAndSessionsArePurged() {
    UUID userId = UUID.randomUUID();
    byte[] userHash = randomBytes();
    jdbc.update(
        "insert into users (id, phone_ciphertext, phone_hash) values (?, ?, ?)",
        userId,
        randomBytes(),
        userHash);

    byte[] oldPhone = randomBytes();
    byte[] newPhone = randomBytes();
    jdbc.update(
        """
        insert into otp_challenges (phone_hash, code_hash, max_attempts, expires_at, consumed_at)
        values (?, ?, 5, now() - interval '2 days', null)
        """,
        oldPhone,
        randomBytes());
    jdbc.update(
        """
        insert into otp_challenges (phone_hash, code_hash, max_attempts, expires_at, consumed_at)
        values (?, ?, 5, now() + interval '1 hour', null)
        """,
        newPhone,
        randomBytes());
    jdbc.update(
        """
        insert into user_sessions (user_id, token_hash, created_at, expires_at)
        values (?, ?, now() - interval '3 days', now() - interval '2 days')
        """,
        userId,
        randomBytes());
    jdbc.update(
        """
        insert into user_sessions (user_id, token_hash, created_at, expires_at)
        values (?, ?, now(), now() + interval '1 hour')
        """,
        userId,
        randomBytes());

    retention.purge();

    assertThat(count("select count(*) from otp_challenges where phone_hash = ?", oldPhone))
        .isZero();
    assertThat(count("select count(*) from otp_challenges where phone_hash = ?", newPhone))
        .isEqualTo(1);
    assertThat(count("select count(*) from user_sessions where user_id = ?", userId)).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from user_sessions where user_id = ? and expires_at < now()",
                userId))
        .isZero();
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private static byte[] randomBytes() {
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    return bytes;
  }
}
