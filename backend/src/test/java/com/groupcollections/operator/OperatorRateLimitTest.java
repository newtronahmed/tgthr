package com.groupcollections.operator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OperatorRateLimitTest {

  @Test
  void eleventhLoginFromOneIpReturns429() throws Exception {
    OperatorAuthService auth = mock(OperatorAuthService.class);
    when(auth.beginLogin(any(), any()))
        .thenReturn(
            new OperatorAuthService.LoginChallenge(
                "challenge-token", Instant.now().plusSeconds(300)));
    OperatorSessionService sessions = mock(OperatorSessionService.class);
    Clock clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC);
    OperatorRateLimiter limiter =
        new OperatorRateLimiter(clock, true, 10, 20, new SimpleMeterRegistry());
    OperatorAuthController controller =
        new OperatorAuthController(auth, sessions, limiter, clock, true);
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new OperatorProblemAdvice())
            .build();

    for (int attempt = 0; attempt < 10; attempt++) {
      mvc.perform(
              post("/operator/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"username":"operator.one","password":"correct horse battery staple"}
                      """))
          .andExpect(status().isOk());
    }

    mvc.perform(
            post("/operator/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"username":"operator.one","password":"correct horse battery staple"}
                    """))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }
}
