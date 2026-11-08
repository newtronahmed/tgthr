package com.groupcollections.goal;

import com.groupcollections.identity.OrganizerAuthFilter;
import com.groupcollections.identity.OrganizerUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/organizer/goals")
public class OrganizerGoalController {

  private final OrganizerGoalService goals;

  public OrganizerGoalController(OrganizerGoalService goals) {
    this.goals = goals;
  }

  @PostMapping
  public ResponseEntity<OrganizerGoal> create(
      @RequestAttribute(OrganizerAuthFilter.USER_ATTRIBUTE) OrganizerUser user,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateGoalRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(goals.create(user, idempotencyKey, request));
  }

  @GetMapping
  public List<OrganizerGoal> list(
      @RequestAttribute(OrganizerAuthFilter.USER_ATTRIBUTE) OrganizerUser user) {
    return goals.list(user);
  }

  @GetMapping("/{id}")
  public OrganizerGoal get(
      @RequestAttribute(OrganizerAuthFilter.USER_ATTRIBUTE) OrganizerUser user,
      @PathVariable UUID id) {
    return goals.get(user, id);
  }

  @PostMapping("/{id}/close")
  public OrganizerGoal close(
      @RequestAttribute(OrganizerAuthFilter.USER_ATTRIBUTE) OrganizerUser user,
      @PathVariable UUID id) {
    return goals.close(user, id);
  }
}
