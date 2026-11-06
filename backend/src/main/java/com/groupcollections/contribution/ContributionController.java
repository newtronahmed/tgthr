package com.groupcollections.contribution;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
  private final PublicGoalService publicGoals;
  private final RateLimiter rateLimiter;
  private final PayerPhoneCipher phones;
  private final ContributionQuoteService quotes;

  public ContributionController(
      ContributionService contributions,
      PublicGoalService publicGoals,
      RateLimiter rateLimiter,
      PayerPhoneCipher phones,
      ContributionQuoteService quotes) {
    this.contributions = contributions;
    this.publicGoals = publicGoals;
    this.rateLimiter = rateLimiter;
    this.phones = phones;
    this.quotes = quotes;
  }

  @GetMapping("/{slug}")
  public PublicGoalView goal(@PathVariable String slug) {
    return publicGoals.get(slug);
  }

  @PostMapping("/{slug}/contribution-quote")
  public FeeQuote quote(
      @PathVariable String slug,
      @Valid @RequestBody ContributionQuoteRequest request,
      HttpServletRequest servletRequest) {
    rateLimiter.checkIp(servletRequest.getRemoteAddr());
    return quotes.quote(slug, request.amountPesewas());
  }

  @PostMapping("/{slug}/contributions")
  public ResponseEntity<ContributionStatus> create(
      @PathVariable String slug,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreateContributionRequest request,
      HttpServletRequest servletRequest) {
    if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 64) {
      throw new InvalidRequestException("Idempotency-Key must be 8 to 64 characters");
    }
    rateLimiter.check(servletRequest.getRemoteAddr(), phones.hash(request.momoNumber()));
    return ResponseEntity.accepted().body(contributions.create(slug, idempotencyKey, request));
  }
}
