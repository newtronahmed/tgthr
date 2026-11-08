package com.groupcollections.goal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OrganizerGoalProblemAdvice {

  @ExceptionHandler(MissingRequestHeaderException.class)
  ProblemDetail missingHeader(MissingRequestHeaderException failure) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key is required");
    problem.setTitle("Invalid request");
    problem.setProperty("code", "INVALID_REQUEST");
    return problem;
  }

  @ExceptionHandler(OrganizerGoalException.class)
  ProblemDetail organizerGoal(OrganizerGoalException failure) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.valueOf(failure.status()), failure.getMessage());
    problem.setTitle(failure.title());
    problem.setProperty("code", failure.code());
    return problem;
  }
}
