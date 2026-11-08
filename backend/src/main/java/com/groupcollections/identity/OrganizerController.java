package com.groupcollections.identity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrganizerController {

  @GetMapping("/organizer/me")
  public OrganizerProfile me(
      @RequestAttribute(OrganizerAuthFilter.USER_ATTRIBUTE) OrganizerUser user) {
    return OrganizerProfile.from(user);
  }
}
