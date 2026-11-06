package com.groupcollections.contribution;

import com.groupcollections.goal.AmountValidationException;
import com.groupcollections.goal.GoalNotAcceptingException;
import com.groupcollections.goal.GoalNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain failures to RFC 9457 problems with the stable codes in the spec. */
@RestControllerAdvice
public class ContributionProblemAdvice {

  @ExceptionHandler(GoalNotFoundException.class)
  ProblemDetail goalNotFound(GoalNotFoundException failure) {
    return problem(HttpStatus.NOT_FOUND, "Goal not found", "GOAL_NOT_FOUND", failure.getMessage());
  }

  @ExceptionHandler(GoalNotAcceptingException.class)
  ProblemDetail goalNotAccepting(GoalNotAcceptingException failure) {
    return problem(
        HttpStatus.CONFLICT,
        "Goal not accepting contributions",
        "GOAL_NOT_ACCEPTING",
        failure.getMessage());
  }

  @ExceptionHandler(AmountValidationException.class)
  ProblemDetail amountInvalid(AmountValidationException failure) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Amount not allowed",
        failure.code(),
        failure.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail requestInvalid(MethodArgumentNotValidException failure) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Invalid request",
        "INVALID_REQUEST",
        failure.getBody().getDetail());
  }

  private static ProblemDetail problem(
      HttpStatus status, String title, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setProperty("code", code);
    return problem;
  }
}
