package com.groupcollections.contribution;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/goals")
public class ContributionController {

  private final ContributionService contributions;

  public ContributionController(ContributionService contributions) {
    this.contributions = contributions;
  }

  @PostMapping("/{slug}/contributions")
  public ResponseEntity<ContributionStatus> create(
      @PathVariable String slug,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreateContributionRequest request) {
    if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 64) {
      throw new InvalidRequestException("Idempotency-Key must be 8 to 64 characters");
    }
    return ResponseEntity.accepted().body(contributions.create(slug, idempotencyKey, request));
  }
}
