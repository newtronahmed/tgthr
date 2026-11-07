package com.groupcollections.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class PaystackSignatureTest {

  private static final String SECRET = "test-secret";
  private static final String BODY =
      "{\"event\":\"charge.success\",\"data\":{\"reference\":\"ref-1\",\"status\":\"success\"}}";

  private final PaystackSignature signatures = new PaystackSignature(SECRET);

  @Test
  void acceptsThePartnerSignatureOverTheRawBody() {
    assertThat(signatures.isValid(BODY, sign(BODY, SECRET))).isTrue();
  }

  @Test
  void rejectsATamperedBody() {
    assertThat(signatures.isValid(BODY + " ", sign(BODY, SECRET))).isFalse();
  }

  @Test
  void rejectsASignatureMadeWithAnotherSecret() {
    assertThat(signatures.isValid(BODY, sign(BODY, "another-secret"))).isFalse();
  }

  @Test
  void rejectsAMissingOrBlankSignature() {
    assertThat(signatures.isValid(BODY, null)).isFalse();
    assertThat(signatures.isValid(BODY, " ")).isFalse();
  }

  @Test
  void rejectsANonHexSignature() {
    assertThat(signatures.isValid(BODY, "not-a-hex-signature")).isFalse();
  }

  @Test
  void verifiesRawBytesForNonAsciiBodies() {
    String body = "{\"note\":\"café\"}";
    assertThat(signatures.isValid(body.getBytes(StandardCharsets.UTF_8), sign(body, SECRET)))
        .isTrue();
  }

  private static String sign(String body, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA512");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
