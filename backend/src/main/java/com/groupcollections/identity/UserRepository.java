package com.groupcollections.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

  private final JdbcTemplate jdbc;

  public UserRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public OrganizerUser findOrCreate(
      byte[] phoneHash, byte[] phoneCiphertext, String displayName, String legalName, Instant now) {
    Timestamp timestamp = Timestamp.from(now);
    jdbc.update(
        """
        insert into users
            (phone_ciphertext, phone_hash, display_name, legal_name, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?)
        on conflict (phone_hash) do nothing
        """,
        phoneCiphertext,
        phoneHash,
        displayName,
        legalName,
        timestamp,
        timestamp);
    if (displayName != null || legalName != null) {
      jdbc.update(
          """
          update users
          set display_name = coalesce(?, display_name),
              legal_name = coalesce(?, legal_name),
              updated_at = ?
          where phone_hash = ?
          """,
          displayName,
          legalName,
          timestamp,
          phoneHash);
    }
    return jdbc
        .query(
            "select id, display_name, legal_name, created_at from users where phone_hash = ?",
            (resultSet, rowNumber) -> map(resultSet),
            phoneHash)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("could not load the organizer"));
  }

  public Optional<byte[]> findPhoneCiphertext(UUID id) {
    return jdbc
        .query("select phone_ciphertext from users where id = ?", (rs, row) -> rs.getBytes(1), id)
        .stream()
        .findFirst()
        .filter(value -> value != null);
  }

  public Optional<byte[]> findPhoneHash(UUID id) {
    return jdbc
        .query("select phone_hash from users where id = ?", (rs, row) -> rs.getBytes(1), id)
        .stream()
        .findFirst();
  }

  public Optional<OrganizerUser> findById(UUID id) {
    return jdbc
        .query(
            "select id, display_name, legal_name, created_at from users where id = ?",
            (resultSet, rowNumber) -> map(resultSet),
            id)
        .stream()
        .findFirst();
  }

  private static OrganizerUser map(ResultSet resultSet) throws SQLException {
    OffsetDateTime createdAt = resultSet.getObject("created_at", OffsetDateTime.class);
    return new OrganizerUser(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("display_name"),
        resultSet.getString("legal_name"),
        createdAt == null ? null : createdAt.toInstant());
  }
}
