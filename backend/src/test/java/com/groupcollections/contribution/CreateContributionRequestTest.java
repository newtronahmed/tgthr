package com.groupcollections.contribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.groupcollections.payments.Network;
import org.junit.jupiter.api.Test;

class CreateContributionRequestTest {

  @Test
  void toStringOmitsThePhoneNumber() {
    CreateContributionRequest request =
        new CreateContributionRequest(5_000L, "0240000001", Network.MTN, "Kofi", false);

    assertThat(request.toString()).doesNotContain("0240000001");
  }
}
