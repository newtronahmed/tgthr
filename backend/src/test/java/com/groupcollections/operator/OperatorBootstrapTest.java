package com.groupcollections.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorBootstrapTest {

  private final OperatorAdminService admin = mock(OperatorAdminService.class);

  @TempDir Path directory;

  @Test
  void writesTheEnrollmentTokenToTheConfiguredFile() throws IOException {
    Path tokenFile = directory.resolve("bootstrap-token");
    when(admin.bootstrapFirstAdmin("admin.one"))
        .thenReturn(
            Optional.of(
                new OperatorCreated(
                    UUID.randomUUID(), "admin.one", "one-time-token", Instant.now())));

    new OperatorBootstrap(admin, "bootstrap-token", "admin.one", tokenFile.toString()).run(null);

    assertThat(Files.readString(tokenFile, StandardCharsets.UTF_8))
        .isEqualTo("OPERATOR_BOOTSTRAP_ENROLLMENT_TOKEN=one-time-token" + System.lineSeparator());
    verify(admin).bootstrapFirstAdmin("admin.one");
  }

  @Test
  void writesNoTokenWhenAnOperatorAlreadyExists() throws IOException {
    Path tokenFile = directory.resolve("bootstrap-token");
    when(admin.bootstrapFirstAdmin("admin.one")).thenReturn(Optional.empty());

    new OperatorBootstrap(admin, "bootstrap-token", "admin.one", tokenFile.toString()).run(null);

    assertThat(tokenFile).doesNotExist();
  }

  @Test
  void doesNothingWhenTheBootstrapTokenIsBlank() {
    new OperatorBootstrap(admin, "", "admin.one", directory.resolve("token").toString()).run(null);

    verifyNoInteractions(admin);
  }

  @Test
  void failsStartupWhenTheTokenIsSetWithoutAUsername() {
    assertThatThrownBy(
            () ->
                new OperatorBootstrap(admin, "token", "", directory.resolve("token").toString())
                    .run(null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void failsStartupWhenTheTokenIsSetWithoutATokenFile() {
    assertThatThrownBy(() -> new OperatorBootstrap(admin, "token", "admin.one", "").run(null))
        .isInstanceOf(IllegalStateException.class);
  }
}
