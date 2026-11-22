package com.cristianatienza.cms.security;

import static com.cristianatienza.cms.security.AuthStates.FACTOR_PASSWORD;
import static com.cristianatienza.cms.security.AuthStates.STATE_ENROLL;

import com.cristianatienza.cms.security.ApiErrors.LoginFailedException;
import com.cristianatienza.cms.security.AppUserRepository.AppUser;
import com.cristianatienza.cms.security.AuthStates.SessionUser;
import com.cristianatienza.cms.security.AuthStates.State;
import com.cristianatienza.cms.security.devices.TrustedDevices;
import com.cristianatienza.cms.security.devices.TrustedDevices.Presentation;
import com.cristianatienza.cms.security.devices.TrustedDevices.Trust;
import com.cristianatienza.cms.security.limits.LoginLimiter.Keys;
import com.cristianatienza.cms.security.passkey.ChallengeStore;
import com.cristianatienza.cms.security.passkey.PasskeyStore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Autenticación con contraseña y sesión (fase 6.2.2). Tras la contraseña, la sesión queda en P1
 * (registro pendiente y ninguna passkey: solo registrar una) o P2 (falta la passkey). El segundo
 * factor y la recuperación están en PasskeyController y RecoveryController (6.2.3).
 *
 * <p>IMPORTANTE: no exponer a Internet hasta aprobar la 6.2.4 y publicarla con su proxy (6.9).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/auth")
public class AuthController {

  /** Límites de entrada: evitan cuerpos enormes y entradas absurdas para Argon2id. */
  static final int MAX_EMAIL = 254;
  /** También el máximo de la política de la herramienta de seguridad (6.2.6), en unidades UTF-16. */
  public static final int MAX_PASSWORD = 1024;

  public record LoginRequest(String email, String password) {
    @Override
    public String toString() {
      return "LoginRequest[email=?, password=?]";
    }
  }

  public record RevokeAllRequest(Boolean devices) {}

  /** Estado sin datos personales; en P2 y F, cuántas passkeys hay utilizables y suspendidas. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record StateResponse(State state, PasskeyStore.Summary passkeys) {}

  private final AppUserRepository users;
  private final PasswordHashing hashing;
  private final SecurityContextRepository contexts;
  private final CookieCsrfTokenRepository csrfTokens;
  private final FindByIndexNameSessionRepository<?> sessions;
  private final Clock clock;
  private final PasskeyStore passkeys;
  private final ChallengeStore challenges;
  private final AuthSessions authSessions;
  private final AttemptGuard attempts;
  private final SecurityEvents events;
  private final TrustedDevices devices;
  private final AccountLock accountLock;

  public AuthController(AppUserRepository users, PasswordHashing hashing, SecurityContextRepository contexts,
      CookieCsrfTokenRepository csrfTokens, FindByIndexNameSessionRepository<?> sessions, Clock clock,
      PasskeyStore passkeys, ChallengeStore challenges, AuthSessions authSessions, AttemptGuard attempts,
      SecurityEvents events, TrustedDevices devices, AccountLock accountLock) {
    this.users = users;
    this.hashing = hashing;
    this.contexts = contexts;
    this.csrfTokens = csrfTokens;
    this.sessions = sessions;
    this.clock = clock;
    this.passkeys = passkeys;
    this.challenges = challenges;
    this.authSessions = authSessions;
    this.attempts = attempts;
    this.events = events;
    this.devices = devices;
    this.accountLock = accountLock;
  }

  /** Emite (o devuelve) el token CSRF: el CMS lo llama al cargar y tras iniciar o cerrar sesión. */
  @GetMapping("/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
  StateResponse login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
    if (body == null || body.email() == null || body.password() == null || body.email().isBlank()
        || body.email().length() > MAX_EMAIL || body.password().isEmpty() || body.password().length() > MAX_PASSWORD) {
      throw new IllegalArgumentException("solicitud de inicio de sesión no válida");
    }
    AppUser user = users.findByEmail(body.email().strip()).orElse(null);
    // Dispositivo de confianza (solo atenúa los límites) y comprobación previa, antes de Argon2id.
    Trust trust = user == null ? new Trust(Presentation.NONE, null, null) : attempts.evaluateDevice(request, user.id());
    Keys keys = attempts.keys(request, body.email(), trust);
    attempts.check(keys, user == null ? null : user.id());
    // Siempre un cálculo completo de Argon2id (contra un hash ficticio si no existe el usuario).
    boolean passwordOk = hashing.matches(body.password(), user == null ? null : user.passwordHash());
    if (user == null || !passwordOk || !user.enabled()) {
      // Contadores primero (confirmados), auditoría después (E2). Mismo 401 en los tres casos.
      attempts.failure(keys, trust, user == null ? null : user.id(), "login.password_failed",
          user == null ? Map.of("reason", "unknown_user", "typedEmailHmac", attempts.typedEmailHmac(body.email()))
              : Map.of("reason", passwordOk ? "disabled" : "wrong_password"));
      throw new LoginFailedException();
    }
    // Con la cuenta bloqueada frente a la recuperación de emergencia (H7): el sello debe seguir
    // siendo el leído junto con el hash que validó la contraseña.
    return accountLock.forLogin(user.id(), user.securityStamp(),
        () -> grantPasswordState(user, trust, request, response));
  }

  private StateResponse grantPasswordState(AppUser user, Trust trust, HttpServletRequest request,
      HttpServletResponse response) {
    // Sin auditoría no hay acceso (E2): se escribe antes de crear la sesión.
    events.record("login.password_succeeded", user.id(), Map.of("device", trust.presentation().name()));

    // Último acceso: contraseña correcta y usuario activo (no cambia updated_at ni enroll_required).
    Instant now = clock.instant();
    users.recordLogin(user.id(), now);

    List<GrantedAuthority> authorities = new ArrayList<>();
    authorities.add(FactorGrantedAuthority.fromAuthority(FACTOR_PASSWORD));
    // P1 solo si el registro está pendiente y aún no hay ninguna passkey: si ya registró una (y no
    // llegó a autenticarse con ella), debe usarla (P2), no registrar otra sin verificación.
    if (user.enrollRequired() && passkeys.count(user.id()) == 0) {
      authorities.add(new SimpleGrantedAuthority(STATE_ENROLL));
    }
    Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
        new SessionUser(user.id(), user.role()), null, authorities);

    // Sesión nueva en cada inicio de sesión (contra la fijación de sesión), por migración: la
    // anterior se borra y una petición simultánea con su identificador no puede resucitarla.
    HttpSession session = authSessions.renew(request);
    session.setAttribute(AuthStates.AUTHENTICATED_AT, now);
    session.setAttribute(AuthStates.SECURITY_STAMP, user.securityStamp());
    AttemptGuard.remember(session, trust);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    rotateCsrfToken(request, response);
    return new StateResponse(AuthStates.of(authentication), null);
  }

  @GetMapping("/me")
  StateResponse me(Authentication authentication) {
    State state = AuthStates.of(authentication);
    PasskeyStore.Summary summary = state == State.PASSKEY_REQUIRED || state == State.AUTHENTICATED
        ? passkeys.summary(((SessionUser) authentication.getPrincipal()).id()) : null;
    return new StateResponse(state, summary);
  }

  /** Acción protectora: se completa aunque falle la auditoría, que se escribe después (E2). */
  @PostMapping("/logout")
  ResponseEntity<Void> logout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
    UUID userId = authentication.getPrincipal() instanceof SessionUser user ? user.id() : null;
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    rotateCsrfToken(request, response);
    events.recordBestEffort("session.logout", userId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Cierra todas las sesiones del usuario, también la actual (solo en F), y por defecto revoca sus
   * dispositivos de confianza ({@code {"devices": false}} los conserva). Acción protectora: cada
   * paso se confirma en su transacción y la auditoría se escribe después, sin poder deshacerlos (E2).
   */
  @PostMapping("/sessions/revoke-all")
  ResponseEntity<Void> revokeAll(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
      @RequestBody(required = false) RevokeAllRequest body) {
    UUID userId = ((SessionUser) authentication.getPrincipal()).id();
    sessions.findByPrincipalName(authentication.getName()).keySet().forEach(sessions::deleteById);
    challenges.deleteForUser(userId);
    boolean revokeDevices = body == null || body.devices() == null || body.devices();
    int revoked = revokeDevices ? devices.revokeAll(userId, "revoke_all") : 0;
    events.recordBestEffort("session.revoke_all", userId, Map.of("devices", Integer.toString(revoked)));
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    rotateCsrfToken(request, response);
    return ResponseEntity.noContent().build();
  }

  /** Token CSRF nuevo tras cambiar el estado de autenticación (Spring lo retira al autenticar). */
  private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
    csrfTokens.saveToken(null, request, response);
    csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
  }
}
