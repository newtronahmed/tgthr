package com.groupcollections.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Links a test to the acceptance criteria or domain invariants it proves, for example
 * {@code @Criterion("AC-CONTRIB-004")}. {@code tools/check_traceability.py} fails the build when a
 * spec ID has no test or a test names an ID no spec defines (Constitution III.2).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Criterion {

  String[] value();
}
