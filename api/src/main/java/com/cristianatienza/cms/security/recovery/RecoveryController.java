package com.cristianatienza.cms.security.recovery;

import static com.cristianatienza.cms.security.AuthStates.FACTOR_RECOVERY;
import static com.cristianatienza.cms.security.AuthStates.STATE_ENROLL;

import com.cristianatienza.cms.security.AccountLock;
import com.cristianatienza.cms.security.ApiErrors.AuthFailure;
import com.cristianatienza.cms.security.AttemptGuard;
import com.cristianatienza.cms.security.AuthController.StateResponse;
import com.cristianatienza.cms.security.AuthSessions;
import com.cristianatienza.cms.security.AuthStates;
import com.cristianatienza.cms.security.AuthStates.SessionUser;
import com.cristianatienza.cms.security.SecurityEvents;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Códigos de recuperación (fase 6.2.3): canje en P2 (pasa a R, que solo permite registrar una
 * passkey nueva) y generación en F con verificación reciente. Un código nunca da FACTOR_WEBAUTHN.
 *
 * <p>IMPORTANTE: no exponer a Internet hasta aprobar la 6.2.4 y publicarla con su proxy (6.9).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/auth")
public class RecoveryController {

  static final String FAILURE_RECOVERY = "recovery";

  public record RedeemRequest(String code) {
    @Override
    public String toString() {
      return "RedeemRequest[code=?]";
    }
  }

  public record CodesResponse(List<String> codes) {
    @Override
    public String toString() {
      return "CodesResponse[codes=?]";
    }
  }

  private final RecoveryCodes codes;
  private final AuthSessions sessions;
  private final SecurityEvents events;
  private final AttemptGuard attempts;
  private final AccountLock accountLock;

  RecoveryController(RecoveryCodes codes, AuthSessions sessions, SecurityEvents events, AttemptGuard attempts,
      AccountLock accountLock) {
    this.codes = codes;
    this.sessions = sessions;
    this.events = events;
    this.attempts = attempts;
    this.accountLock = accountLock;
  }

  /** Canje (P2): sesión en R, las demás sesiones del usuario se cierran. */
  @PostMapping(path = "/recovery", consumes = MediaType.APPLICATION_JSON_VALUE)
  StateResponse redeem(@RequestBody RedeemRequest body, Authentication authentication, HttpServletRequest request,
      HttpServletResponse response) {
    SessionUser user = AuthSessions.user(authentication);
    String normalized = RecoveryCodes.normalize(body == null ? null : body.code());
    // Todo el canje, bajo el bloqueo de la cuenta (H7).
    return accountLock.forSession(request, response, user, "recovery.redeem",
        () -> redeem(authentication, user, normalized, request, response));
  }

  private StateResponse redeem(Authentication authentication, SessionUser user, String normalized,
      HttpServletRequest request, HttpServletResponse response) {
    if (!codes.redeem(user.id(), normalized)) {
      // Contadores primero (D7 y límites, confirmados) y auditoría después (E2).
      boolean closed = sessions.recordFailure(request, response, FAILURE_RECOVERY);
      attempts.secondFactorFailure(request, user, "recovery.code_rejected", Map.of());
      if (closed) {
        events.recordBestEffort("session.closed_after_failures", user.id(), Map.of("kind", FAILURE_RECOVERY));
      }
      throw new AuthFailure(HttpStatus.UNAUTHORIZED, "código no válido");
    }
    // Sin auditoría no se pasa a R (E2). El código ya quedó usado: es lo seguro (documentado).
    events.record("recovery.code_used", user.id(), Map.of("remaining", Integer.toString(codes.remaining(user.id()))));
    sessions.resetFailures(request, FAILURE_RECOVERY);
    Authentication recovery = sessions.switchTo(request, response, user, AuthSessions.authorities(authentication,
        a -> false, List.of(new SimpleGrantedAuthority(FACTOR_RECOVERY), new SimpleGrantedAuthority(STATE_ENROLL))));
    int closed = sessions.closeOtherSessions(user, request.getSession().getId());
    events.recordBestEffort("recovery.other_sessions_closed", user.id(), Map.of("closedSessions", Integer.toString(closed)));
    return new StateResponse(AuthStates.of(recovery), null);
  }

  /** Lote nuevo (F con verificación reciente): los anteriores dejan de valer. Se muestran una vez. */
  @PostMapping("/recovery-codes")
  ResponseEntity<CodesResponse> generate(Authentication authentication, HttpServletRequest request,
      HttpServletResponse response) {
    SessionUser user = AuthSessions.user(authentication);
    // Bajo el bloqueo de la cuenta (H7): unos códigos nuevos no pueden sobrevivir a una recuperación.
    return accountLock.forSession(request, response, user, "recovery.codes", () -> {
      List<String> generated = codes.generate(user.id());
      events.record("recovery.codes_generated", user.id(), Map.of("count", Integer.toString(generated.size())));
      return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
          .body(new CodesResponse(generated));
    });
  }
}
