package com.groupcollections.operator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deploy-time first-admin bootstrap. It runs only while no operator exists and writes the one-time
 * enrollment token to a file, never to application logs. The file should be a mounted secret or a
 * short-lived deploy artefact.
 */
@Component
@ConditionalOnProperty(name = "collections.operator.enabled", havingValue = "true")
public class OperatorBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(OperatorBootstrap.class);

  private final OperatorAdminService admin;
  private final String token;
  private final String username;
  private final String tokenFile;

  public OperatorBootstrap(
      OperatorAdminService admin,
      @Value("${collections.operator.bootstrap.token:}") String token,
      @Value("${collections.operator.bootstrap.username:}") String username,
      @Value("${collections.operator.bootstrap.token-file:}") String tokenFile) {
    this.admin = admin;
    this.token = token;
    this.username = username;
    this.tokenFile = tokenFile;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (token == null || token.isBlank()) {
      return;
    }
    if (username == null || username.isBlank()) {
      throw new IllegalStateException(
          "OPERATOR_BOOTSTRAP_USERNAME is required when bootstrap is enabled");
    }
    if (tokenFile == null || tokenFile.isBlank()) {
      throw new IllegalStateException(
          "OPERATOR_BOOTSTRAP_TOKEN_FILE is required when bootstrap is enabled");
    }
    admin
        .bootstrapFirstAdmin(username, this::writeToken)
        .ifPresentOrElse(
            created ->
                log.info("operator bootstrap created the first admin; enrollment token written"),
            () -> log.info("operator bootstrap skipped because an operator already exists"));
  }

  private void writeToken(OperatorCreated created) {
    Path path = Path.of(tokenFile);
    try {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(
          path,
          "OPERATOR_BOOTSTRAP_ENROLLMENT_TOKEN="
              + created.enrollmentToken()
              + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      try {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        // Non-POSIX filesystems fall back to the deployment's file permissions.
      }
    } catch (IOException failure) {
      throw new IllegalStateException("could not write the bootstrap enrollment token", failure);
    }
  }
}
