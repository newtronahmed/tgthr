package com.groupcollections.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.payments.Network;
import com.groupcollections.support.FakePaystackServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

class PaystackAccountResolverTest {

  private FakePaystackServer fake;
  private PaystackAccountResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakePaystackServer("test-secret");
    RestClient restClient =
        RestClient.builder()
            .baseUrl(fake.baseUrl())
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-secret")
            .build();
    resolver = new PaystackAccountResolver(restClient);
  }

  @AfterEach
  void tearDown() {
    fake.close();
  }

  @Test
  void resolvesAnE164NumberWithTheMtnTransferCode() {
    String name = resolver.resolve("+233551234987", Network.MTN);

    assertThat(name).isEqualTo("Kofi Mensah");
    assertThat(fake.resolveQueries().get(0))
        .contains("account_number=0551234987")
        .contains("bank_code=MTN");
  }

  @Test
  void mapsTelecelToTheVodafoneTransferCode() {
    assertThat(resolver.resolve("+233201234567", Network.TELECEL)).isEqualTo("Kofi Mensah");
    assertThat(fake.resolveQueries().get(0)).contains("bank_code=VOD");
  }

  @Test
  void unresolvedAccountReturnsNull() {
    fake.resolveScenario("NOT_FOUND");

    assertThat(resolver.resolve("+233551234987", Network.MTN)).isNull();
  }

  @Test
  void providerFailureIsUnavailable() {
    fake.resolveScenario("UNKNOWN");

    assertThatThrownBy(() -> resolver.resolve("+233551234987", Network.MTN))
        .isInstanceOfSatisfying(
            OrganizerGoalException.class,
            failure -> assertThat(failure.code()).isEqualTo("DESTINATION_CHECK_UNAVAILABLE"));
  }

  @Test
  void unauthorizedIsUnavailableNotUnresolved() {
    fake.resolveScenario("UNAUTHORIZED");

    assertThatThrownBy(() -> resolver.resolve("+233551234987", Network.MTN))
        .isInstanceOfSatisfying(
            OrganizerGoalException.class,
            failure -> assertThat(failure.code()).isEqualTo("DESTINATION_CHECK_UNAVAILABLE"));
  }

  @Test
  void rateLimitedIsUnavailableNotUnresolved() {
    fake.resolveScenario("RATE_LIMITED");

    assertThatThrownBy(() -> resolver.resolve("+233551234987", Network.MTN))
        .isInstanceOfSatisfying(
            OrganizerGoalException.class,
            failure -> assertThat(failure.code()).isEqualTo("DESTINATION_CHECK_UNAVAILABLE"));
  }

  @Test
  void missingNetworkIsUnavailable() {
    assertThatThrownBy(() -> resolver.resolve("+233551234987", null))
        .isInstanceOfSatisfying(
            OrganizerGoalException.class,
            failure -> assertThat(failure.code()).isEqualTo("DESTINATION_CHECK_UNAVAILABLE"));
  }
}
