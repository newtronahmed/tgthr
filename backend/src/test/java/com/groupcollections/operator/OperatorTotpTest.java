package com.groupcollections.operator;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class OperatorTotpTest {

  private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(US_ASCII);

  @Test
  void matchesRfc6238Sha1VectorsAtSixDigits() {
    assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(59))).isEqualTo("287082");
    assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(1_111_111_109))).isEqualTo("081804");
    assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(1_111_111_111))).isEqualTo("050471");
    assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(1_234_567_890))).isEqualTo("005924");
    assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(2_000_000_000))).isEqualTo("279037");
    assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(20_000_000_000L))).isEqualTo("353130");
  }

  @Test
  void acceptsOnlyThePreviousCurrentAndNextSteps() {
    String previous = Totp.code(RFC_SECRET, Instant.ofEpochSecond(30));
    String current = Totp.code(RFC_SECRET, Instant.ofEpochSecond(60));
    String next = Totp.code(RFC_SECRET, Instant.ofEpochSecond(90));
    String tooOld = Totp.code(RFC_SECRET, Instant.ofEpochSecond(0));
    String tooFar = Totp.code(RFC_SECRET, Instant.ofEpochSecond(120));

    assertThat(Totp.verify(RFC_SECRET, previous, Instant.ofEpochSecond(60), -1))
        .isEqualTo(OptionalLong.of(1));
    assertThat(Totp.verify(RFC_SECRET, current, Instant.ofEpochSecond(60), -1))
        .isEqualTo(OptionalLong.of(2));
    assertThat(Totp.verify(RFC_SECRET, next, Instant.ofEpochSecond(60), -1))
        .isEqualTo(OptionalLong.of(3));
    assertThat(Totp.verify(RFC_SECRET, tooOld, Instant.ofEpochSecond(60), -1)).isEmpty();
    assertThat(Totp.verify(RFC_SECRET, tooFar, Instant.ofEpochSecond(60), -1)).isEmpty();
  }

  @Test
  void rejectsAReplayedStepButAcceptsTheNextOne() {
    String codeAtStepOne = Totp.code(RFC_SECRET, Instant.ofEpochSecond(59));
    String codeAtStepTwo = Totp.code(RFC_SECRET, Instant.ofEpochSecond(89));

    assertThat(Totp.verify(RFC_SECRET, codeAtStepOne, Instant.ofEpochSecond(59), 0))
        .isEqualTo(OptionalLong.of(1));
    assertThat(Totp.verify(RFC_SECRET, codeAtStepOne, Instant.ofEpochSecond(59), 1)).isEmpty();
    assertThat(Totp.verify(RFC_SECRET, codeAtStepTwo, Instant.ofEpochSecond(59), 1))
        .isEqualTo(OptionalLong.of(2));
  }

  @Test
  void rejectsMalformedCodesWithoutThrowing() {
    assertThat(Totp.verify(RFC_SECRET, null, Instant.ofEpochSecond(59), -1)).isEmpty();
    assertThat(Totp.verify(RFC_SECRET, "", Instant.ofEpochSecond(59), -1)).isEmpty();
    assertThat(Totp.verify(RFC_SECRET, "12345", Instant.ofEpochSecond(59), -1)).isEmpty();
    assertThat(Totp.verify(RFC_SECRET, "1234567", Instant.ofEpochSecond(59), -1)).isEmpty();
    assertThat(Totp.verify(RFC_SECRET, "abcdef", Instant.ofEpochSecond(59), -1)).isEmpty();
    assertThat(Totp.verify(new byte[0], "123456", Instant.ofEpochSecond(59), -1)).isEmpty();
  }

  @Test
  void roundTripsBase32Secrets() {
    String encoded = Base32.encode(RFC_SECRET);
    assertThat(encoded).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    assertThat(Base32.decode(encoded)).isEqualTo(RFC_SECRET);
    assertThat(Base32.decode("gezdgnbvgy3tqojqgezdgnbvgy3tqojq")).isEqualTo(RFC_SECRET);
    assertThat(Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ====")).isEqualTo(RFC_SECRET);
  }

  @Test
  void rejectsInvalidBase32Secrets() {
    for (String invalid : new String[] {"!", "A", "ABC", "0189"}) {
      try {
        Base32.decode(invalid);
        assertThat(false).as("expected invalid base32: %s", invalid).isTrue();
      } catch (IllegalArgumentException expected) {
        assertThat(expected).hasMessageContaining("base32");
      }
    }
  }
}
