package com.groupcollections.identity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IdentityProblemAdvice {

  @ExceptionHandler(IdentityException.class)
  ProblemDetail identity(IdentityException failure) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.valueOf(failure.status()), failure.getMessage());
    problem.setTitle(failure.title());
    problem.setProperty("code", failure.code());
    return problem;
  }
}
