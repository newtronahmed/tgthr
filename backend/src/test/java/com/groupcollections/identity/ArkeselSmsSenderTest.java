package com.groupcollections.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groupcollections.support.FakeArkeselServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArkeselSmsSenderTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private FakeArkeselServer fake;
  private ArkeselSmsSender sender;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakeArkeselServer();
    sender = sender(false);
  }

  @AfterEach
  void tearDown() {
    fake.close();
  }

  @Test
  void sendsTheLocalCountryCodeFormWithTheConfiguredSenderAndKey() throws Exception {
    sender.send("+233551234987", "Your code is 123456");

    assertThat(fake.apiKeys()).containsExactly("test-key");
    JsonNode body = mapper.readTree(fake.bodies().get(0));
    assertThat(body.path("sender").asText()).isEqualTo("Tgthr");
    assertThat(body.path("recipients").get(0).asText()).isEqualTo("233551234987");
    assertThat(body.path("message").asText()).isEqualTo("Your code is 123456");
    assertThat(body.path("sandbox").asBoolean()).isFalse();
  }

  @Test
  void convertsLocalInputAndHonoursSandbox() throws Exception {
    sender(true).send("0551234987", "Your code is 123456");

    JsonNode body = mapper.readTree(fake.bodies().get(0));
    assertThat(body.path("recipients").get(0).asText()).isEqualTo("233551234987");
    assertThat(body.path("sandbox").asBoolean()).isTrue();
  }

  @Test
  void providerErrorIsSmsUnavailable() {
    fake.scenario("ERROR");

    assertThatThrownBy(() -> sender.send("+233551234987", "code"))
        .isInstanceOfSatisfying(
            IdentityException.class,
            failure -> assertThat(failure.code()).isEqualTo("SMS_UNAVAILABLE"));
  }

  @Test
  void unauthorizedIsSmsUnavailable() {
    fake.scenario("UNAUTHORIZED");

    assertThatThrownBy(() -> sender.send("+233551234987", "code"))
        .isInstanceOfSatisfying(
            IdentityException.class,
            failure -> assertThat(failure.code()).isEqualTo("SMS_UNAVAILABLE"));
  }

  @Test
  void serverErrorIsSmsUnavailable() {
    fake.scenario("SERVER");

    assertThatThrownBy(() -> sender.send("+233551234987", "code"))
        .isInstanceOfSatisfying(
            IdentityException.class,
            failure -> assertThat(failure.code()).isEqualTo("SMS_UNAVAILABLE"));
  }

  @Test
  void rejectsMissingKeyAndLongSenderIds() {
    assertThatThrownBy(() -> sender("", "Tgthr", false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> sender("key", "GroupCollections", false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ArkeselSmsSender sender(boolean sandbox) {
    return sender("test-key", "Tgthr", sandbox);
  }

  private ArkeselSmsSender sender(String key, String senderId, boolean sandbox) {
    return new ArkeselSmsSender(
        fake.baseUrl(),
        key,
        senderId,
        sandbox,
        java.time.Duration.ofSeconds(1),
        java.time.Duration.ofSeconds(1));
  }
}
