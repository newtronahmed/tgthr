package com.groupcollections.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorBootstrapTest {

  private final OperatorAdminService admin = mock(OperatorAdminService.class);

  @TempDir Path directory;

  @Test
  void writesTheEnrollmentTokenToTheConfiguredFile() throws IOException {
    Path tokenFile = directory.resolve("bootstrap-token");
    OperatorCreated created =
        new OperatorCreated(UUID.randomUUID(), "admin.one", "one-time-token", Instant.now());
    when(admin.bootstrapFirstAdmin(eq("admin.one"), any()))
        .thenAnswer(
            invocation -> {
              Consumer<OperatorCreated> beforeCommit = invocation.getArgument(1);
              beforeCommit.accept(created);
              return Optional.of(created);
            });

    new OperatorBootstrap(admin, "bootstrap-token", "admin.one", tokenFile.toString()).run(null);

    assertThat(Files.readString(tokenFile, StandardCharsets.UTF_8))
        .isEqualTo("OPERATOR_BOOTSTRAP_ENROLLMENT_TOKEN=one-time-token" + System.lineSeparator());
    verify(admin).bootstrapFirstAdmin(eq("admin.one"), any());
  }

  @Test
  void writesNoTokenWhenAnOperatorAlreadyExists() throws IOException {
    Path tokenFile = directory.resolve("bootstrap-token");
    when(admin.bootstrapFirstAdmin(eq("admin.one"), any())).thenReturn(Optional.empty());

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
