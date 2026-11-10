package com.groupcollections.payout;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PayoutProblemAdvice {

  @ExceptionHandler(PayoutException.class)
  ProblemDetail payout(PayoutException failure) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.valueOf(failure.status()), failure.getMessage());
    problem.setTitle(failure.title());
    problem.setProperty("code", failure.code());
    return problem;
  }
}
