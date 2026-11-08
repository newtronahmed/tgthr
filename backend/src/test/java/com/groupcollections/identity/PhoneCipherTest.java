package com.groupcollections.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.groupcollections.security.PhoneCipher;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class PhoneCipherTest {

  private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
  private static final byte[] MASTER = java.util.Base64.getDecoder().decode(KEY);

  private final PhoneCipher cipher = new PhoneCipher(KEY, new SecureRandom());

  @Test
  void encryptionIsRandomizedAndDoesNotStorePlaintext() {
    byte[] first = cipher.encrypt("+233240000001");
    byte[] second = cipher.encrypt("+233240000001");

    assertThat(first).isNotEqualTo(second);
    assertThat(new String(first, StandardCharsets.UTF_8)).doesNotContain("+233240000001");
  }

  @Test
  void lookupHashMatchesTheContributionDerivation() throws Exception {
    byte[] expected = hmac("lookup", "+233240000001");

    assertThat(cipher.hash("+233240000001")).isEqualTo(expected);
    assertThat(cipher.hash("+233240000002")).isNotEqualTo(expected);
  }

  @Test
  void otpHashesUseASeparateKeyAndAreDeterministic() throws Exception {
    byte[] otp = cipher.hashOtpCode("123456");

    assertThat(otp).isEqualTo(cipher.hashOtpCode("123456"));
    assertThat(otp).isNotEqualTo(cipher.hash("123456"));
    assertThat(otp).isEqualTo(hmac("otp", "123456"));
  }

  @Test
  void shortMasterKeysAreRejected() {
    String shortKey = java.util.Base64.getEncoder().encodeToString(new byte[16]);
    assertThatThrownBy(() -> new PhoneCipher(shortKey, new SecureRandom()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static byte[] hmac(String label, String value) throws Exception {
    Mac derive = Mac.getInstance("HmacSHA256");
    derive.init(new SecretKeySpec(MASTER, "HmacSHA256"));
    byte[] derived = derive.doFinal(label.getBytes(StandardCharsets.UTF_8));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(derived, "HmacSHA256"));
    return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
  }
}
