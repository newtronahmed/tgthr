package com.groupcollections.operator;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/operator/auth")
@ConditionalOnProperty(name = "collections.operator.enabled", havingValue = "true")
public class OperatorEnrollmentController {

  private final OperatorAdminService admin;

  public OperatorEnrollmentController(OperatorAdminService admin) {
    this.admin = admin;
  }

  @PostMapping("/enroll")
  public ResponseEntity<OperatorEnrollmentDetails> enroll(
      @Valid @RequestBody OperatorEnrollmentRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(admin.enroll(request.token(), request.password()));
  }

  @PostMapping("/enroll/confirm")
  public ResponseEntity<Void> confirm(
      @Valid @RequestBody OperatorEnrollmentConfirmRequest request) {
    admin.confirmEnrollment(request.token(), request.code());
    return ResponseEntity.noContent().build();
  }
}
