package com.groupcollections.operator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/operator/operators")
@ConditionalOnProperty(name = "collections.operator.enabled", havingValue = "true")
public class OperatorAdminController {

  private final OperatorAdminService admin;

  public OperatorAdminController(OperatorAdminService admin) {
    this.admin = admin;
  }

  @PostMapping
  public ResponseEntity<OperatorCreated> create(
      @Valid @RequestBody CreateOperatorRequest request,
      @RequestHeader(value = "X-Operator-TOTP", required = false) String totpCode,
      @RequestHeader(value = "X-Operator-Recovery-Code", required = false) String recoveryCode,
      HttpServletRequest servletRequest) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(admin.create(principal(servletRequest), request, totpCode, recoveryCode));
  }

  @GetMapping
  public List<OperatorAdminView> list(HttpServletRequest request) {
    return admin.list(principal(request));
  }

  @GetMapping("/{id}")
  public OperatorAdminView get(@PathVariable UUID id, HttpServletRequest request) {
    return admin.get(principal(request), id);
  }

  @PutMapping("/{id}/roles")
  public OperatorAdminView changeRoles(
      @PathVariable UUID id,
      @Valid @RequestBody ChangeRolesRequest request,
      @RequestHeader(value = "X-Operator-TOTP", required = false) String totpCode,
      @RequestHeader(value = "X-Operator-Recovery-Code", required = false) String recoveryCode,
      HttpServletRequest servletRequest) {
    return admin.changeRoles(principal(servletRequest), id, request, totpCode, recoveryCode);
  }

  @PostMapping("/{id}/disable")
  public OperatorAdminView disable(
      @PathVariable UUID id,
      @RequestHeader(value = "X-Operator-TOTP", required = false) String totpCode,
      @RequestHeader(value = "X-Operator-Recovery-Code", required = false) String recoveryCode,
      HttpServletRequest request) {
    return admin.disable(principal(request), id, totpCode, recoveryCode);
  }

  @PostMapping("/{id}/enable")
  public OperatorAdminView enable(
      @PathVariable UUID id,
      @RequestHeader(value = "X-Operator-TOTP", required = false) String totpCode,
      @RequestHeader(value = "X-Operator-Recovery-Code", required = false) String recoveryCode,
      HttpServletRequest request) {
    return admin.enable(principal(request), id, totpCode, recoveryCode);
  }

  private static OperatorPrincipal principal(HttpServletRequest request) {
    Object principal = request.getAttribute(OperatorAuthFilter.PRINCIPAL_ATTRIBUTE);
    if (principal instanceof OperatorPrincipal operator) {
      return operator;
    }
    throw OperatorException.unauthorized();
  }
}
