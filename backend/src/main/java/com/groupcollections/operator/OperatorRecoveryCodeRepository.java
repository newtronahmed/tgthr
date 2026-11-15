package com.groupcollections.operator;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperatorRecoveryCodeRepository {

  private final JdbcTemplate jdbc;

  public OperatorRecoveryCodeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void replace(UUID operatorId, List<String> hashes) {
    jdbc.update("delete from operator_recovery_codes where operator_id = ?", operatorId);
    for (String hash : hashes) {
      jdbc.update(
          "insert into operator_recovery_codes (operator_id, code_hash) values (?, ?)",
          operatorId,
          hash);
    }
  }

  public Optional<UUID> findUnused(UUID operatorId, String codeHash) {
    return jdbc
        .query(
            """
            select id from operator_recovery_codes
            where operator_id = ? and code_hash = ? and used_at is null
            """,
            (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
            operatorId,
            codeHash)
        .stream()
        .findFirst();
  }

  public int markUsed(UUID id, Instant now) {
    return jdbc.update(
        """
        update operator_recovery_codes
        set used_at = ?
        where id = ? and used_at is null
        """,
        Timestamp.from(now),
        id);
  }
}
