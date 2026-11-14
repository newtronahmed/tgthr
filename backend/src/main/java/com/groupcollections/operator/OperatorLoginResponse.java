package com.groupcollections.operator;

import java.time.Instant;

public record OperatorLoginResponse(String challengeToken, Instant expiresAt) {}
