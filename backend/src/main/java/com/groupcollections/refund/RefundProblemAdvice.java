package com.groupcollections.refund;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RefundProblemAdvice {

  @ExceptionHandler(RefundException.class)
  ProblemDetail refund(RefundException failure) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.valueOf(failure.status()), failure.getMessage());
    problem.setTitle(failure.title());
    problem.setProperty("code", failure.code());
    return problem;
  }
}
