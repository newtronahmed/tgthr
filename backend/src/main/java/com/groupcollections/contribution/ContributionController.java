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
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateContributionRequest request) {
    return ResponseEntity.accepted().body(contributions.create(slug, idempotencyKey, request));
  }
}
