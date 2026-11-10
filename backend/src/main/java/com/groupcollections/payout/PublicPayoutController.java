package com.groupcollections.payout;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/goals/{slug}/payouts")
@ConditionalOnProperty(name = "collections.payout.enabled", havingValue = "true")
public class PublicPayoutController {

  private final PayoutReportService reports;

  public PublicPayoutController(PayoutReportService reports) {
    this.reports = reports;
  }

  @PostMapping("/{payoutId}/report")
  public ResponseEntity<Void> report(
      @PathVariable String slug,
      @PathVariable UUID payoutId,
      @Valid @RequestBody PayoutReportRequest request,
      HttpServletRequest servletRequest) {
    reports.report(slug, payoutId, request, servletRequest.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.ACCEPTED).build();
  }
}
