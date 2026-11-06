package com.groupcollections.contribution;

public final class InvalidSignatureException extends RuntimeException {

  public InvalidSignatureException() {
    super("the callback signature is missing or invalid");
  }
}
