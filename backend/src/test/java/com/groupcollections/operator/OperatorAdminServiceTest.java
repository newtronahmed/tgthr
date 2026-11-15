package com.groupcollections.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OperatorAdminServiceTest {

  private final OperatorRepository operators = mock(OperatorRepository.class);
  private final OperatorEnrollmentRepository enrollments = mock(OperatorEnrollmentRepository.class);
  private final OperatorRecoveryCodeRepository recoveryCodes =
      mock(OperatorRecoveryCodeRepository.class);
  private final OperatorSecretCipher cipher = mock(OperatorSecretCipher.class);
  private final PasswordHasher hasher = mock(PasswordHasher.class);
  private final OperatorAuthService auth = mock(OperatorAuthService.class);
  private final OperatorSessionService sessions = mock(OperatorSessionService.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC);

  private final OperatorAdminService service =
      new OperatorAdminService(
          operators,
          enrollments,
          recoveryCodes,
          cipher,
          hasher,
          auth,
          sessions,
          new SecureRandom(),
          clock,
          new SimpleMeterRegistry(),
          Duration.ofHours(24));

  private final OperatorPrincipal admin =
      new OperatorPrincipal(UUID.randomUUID(), "admin.one", Set.of(OperatorRole.OPERATOR_ADMIN));

  @BeforeEach
  void freshTotpByDefault() {
    org.mockito.Mockito.doNothing().when(auth).requireStepUp(any(), any(), any());
    when(hasher.hash(any())).thenReturn("hashed-pending-password");
    when(cipher.encrypt(any())).thenReturn(new byte[32]);
  }

  @Test
  void lastAdminCannotLoseTheAdminRole() {
    UUID targetId = UUID.randomUUID();
    when(operators.findById(targetId)).thenReturn(Optional.of(operator(targetId)));
    when(operators.roles(targetId)).thenReturn(Set.of(OperatorRole.OPERATOR_ADMIN));
    when(operators.countActiveAdmins()).thenReturn(1L);

    assertThatThrownBy(
            () ->
                service.changeRoles(
                    admin,
                    targetId,
                    new ChangeRolesRequest(List.of("REFUND_OPERATOR")),
                    null,
                    null))
        .isInstanceOfSatisfying(
            OperatorException.class, failure -> assertThat(failure.code()).isEqualTo("LAST_ADMIN"));
    verify(operators).lockAdminGuard();
  }

  @Test
  void lastAdminCannotBeDisabled() {
    UUID targetId = UUID.randomUUID();
    when(operators.findById(targetId)).thenReturn(Optional.of(operator(targetId)));
    when(operators.roles(targetId)).thenReturn(Set.of(OperatorRole.OPERATOR_ADMIN));
    when(operators.countActiveAdmins()).thenReturn(1L);

    assertThatThrownBy(() -> service.disable(admin, targetId, null, null))
        .isInstanceOfSatisfying(
            OperatorException.class, failure -> assertThat(failure.code()).isEqualTo("LAST_ADMIN"));
    verify(operators).lockAdminGuard();
  }

  @Test
  void enableRejectsAnAccountThatIsNotDisabled() {
    UUID targetId = UUID.randomUUID();
    when(operators.findById(targetId))
        .thenReturn(
            Optional.of(
                new Operator(
                    targetId,
                    "target.one",
                    "hash",
                    new byte[0],
                    OperatorStatus.PENDING_ENROLLMENT,
                    0,
                    null,
                    -1)));

    assertThatThrownBy(() -> service.enable(admin, targetId, null, null))
        .isInstanceOfSatisfying(
            OperatorException.class,
            failure -> assertThat(failure.code()).isEqualTo("INVALID_STATUS"));
  }

  @Test
  void operatorCannotDisableThemselves() {
    assertThatThrownBy(() -> service.disable(admin, admin.id(), null, null))
        .isInstanceOfSatisfying(
            OperatorException.class,
            failure -> assertThat(failure.code()).isEqualTo("SELF_DISABLE"));
  }

  @Test
  void bootstrapSkipsWhenAnOperatorAlreadyExists() {
    when(operators.count()).thenReturn(1L);

    assertThat(service.bootstrapFirstAdmin("admin.one", created -> {})).isEmpty();
    verify(operators).lockBootstrap();
    verify(operators).count();
  }

  @Test
  void bootstrapHandsTheTokenToTheWriterBeforeReturning() {
    when(operators.count()).thenReturn(0L);
    AtomicReference<OperatorCreated> written = new AtomicReference<>();

    OperatorCreated created = service.bootstrapFirstAdmin("admin.one", written::set).orElseThrow();

    assertThat(written.get()).isSameAs(created);
    assertThat(created.username()).isEqualTo("admin.one");
    assertThat(created.enrollmentToken()).isNotBlank();
    verify(operators).lockBootstrap();
    verify(operators)
        .insert(any(), eq("admin.one"), any(), any(), eq(OperatorStatus.PENDING_ENROLLMENT), any());
    verify(operators)
        .grantRole(created.id(), OperatorRole.OPERATOR_ADMIN, null, Instant.now(clock));
    verify(enrollments).insert(eq(created.id()), any(), any(), any());
  }

  private static Operator operator(UUID id) {
    return new Operator(id, "target.one", "hash", new byte[0], OperatorStatus.ACTIVE, 0, null, -1);
  }
}
