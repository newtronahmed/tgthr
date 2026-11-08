package com.groupcollections.identity;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only code lookup for the browser E2E; gated by SMS_PROVIDER=dev. */
@RestController
@ConditionalOnProperty(name = "collections.sms.provider", havingValue = "dev")
@Profile("e2e")
public class DevOtpController {

  @GetMapping("/dev/otp/last")
  public ResponseEntity<Map<String, String>> last(@RequestParam String phone) {
    String normalized;
    try {
      normalized = PhoneNormalizer.normalize(phone);
    } catch (IdentityException invalid) {
      return ResponseEntity.badRequest().build();
    }
    return DevSmsSender.lastCode(normalized)
        .map(code -> ResponseEntity.ok(Map.of("code", code)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
