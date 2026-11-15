package com.groupcollections.operator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperatorDtoRedactionTest {

  @Test
  void operatorCreatedHidesTheEnrollmentToken() {
    OperatorCreated created =
        new OperatorCreated(
            UUID.randomUUID(), "admin.one", "secret-enrollment-token", Instant.now());

    assertThat(created.toString()).contains("[REDACTED]").doesNotContain("secret-enrollment-token");
  }

  @Test
  void enrollmentDetailsHideTheSecretUriAndRecoveryCodes() {
    OperatorEnrollmentDetails details =
        new OperatorEnrollmentDetails(
            "JBSWY3DPEHPK3PXP",
            "otpauth://totp/GroupCollections:admin.one?secret=JBSWY3DPEHPK3PXP",
            List.of("ABCDEFGH", "IJKLMNOP"));

    assertThat(details.toString())
        .contains("[REDACTED]", "recoveryCodeCount=2")
        .doesNotContain("JBSWY3DPEHPK3PXP", "ABCDEFGH", "IJKLMNOP");
  }
}
