package com.groupcollections.contribution;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/contributions")
public class ContributionStatusController {

  private final ContributionService contributions;

  public ContributionStatusController(ContributionService contributions) {
    this.contributions = contributions;
  }

  @GetMapping("/{id}")
  public ContributionStatus get(@PathVariable UUID id) {
    return contributions.status(id);
  }
}
