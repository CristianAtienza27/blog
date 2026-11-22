package com.cristianatienza.cms.security.passkey;

import static com.cristianatienza.cms.security.AuthStates.FACTOR_RECOVERY;
import static com.cristianatienza.cms.security.AuthStates.STATE_ENROLL;

import com.cristianatienza.cms.security.AccountLock;
import com.cristianatienza.cms.security.ApiErrors.AuthFailure;
import com.cristianatienza.cms.security.AppUserRepository;
import com.cristianatienza.cms.security.AttemptGuard;
import com.cristianatienza.cms.security.AppUserRepository.AppUser;
import com.cristianatienza.cms.security.AuthController.StateResponse;
import com.cristianatienza.cms.security.AuthSessions;
import com.cristianatienza.cms.security.AuthStates;
import com.cristianatienza.cms.security.AuthStates.SessionUser;
import com.cristianatienza.cms.security.AuthStates.State;
import com.cristianatienza.cms.security.SecurityEvents;
import com.cristianatienza.cms.security.passkey.ChallengeStore.Ceremony;
import com.cristianatienza.cms.security.passkey.PasskeyCeremonies.CeremonyFailure;
import com.cristianatienza.cms.security.passkey.PasskeyCeremonies.CounterFailure;
import com.cristianatienza.cms.security.passkey.PasskeyCeremonies.Issued;
import com.cristianatienza.cms.security.passkey.PasskeyCeremonies.Reason;
import com.cristianatienza.cms.security.passkey.PasskeyCeremonies.Verified;
import com.cristianatienza.cms.security.passkey.PasskeyStore.Credential;
import com.cristianatienza.cms.security.recovery.RecoveryCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Passkeys (fase 6.2.3, D1 y D3): registro (P1, R, o F con step-up), autenticación (P2 → F),
 * step-up (F) y gestión (F). Qué estado puede llamar a cada endpoint lo decide SecurityConfig; aquí
 * se aplican las transiciones. Todas rotan el identificador de sesión y el token CSRF.
 *
 * <p>IMPORTANTE: no exponer a Internet hasta aprobar la 6.2.4 y publicarla con su proxy (6.9).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/auth")
public class PasskeyController {

  static final String FAILURE_PASSKEY = "passkey";
  static final String FAILURE_STEP_UP = "step_up";

  /** Passkey tal como la ve su dueño: sin material criptográfico. */
  public record PasskeyView(UUID id, String label, Instant createdAt, Instant lastUsedAt, boolean suspended,
      boolean backupEligible, boolean backupState, List<String> transports) {}

  private final PasskeyCeremonies ceremonies;
  private final PasskeyStore passkeys;
  private final AppUserRepository users;
  private final AuthSessions sessions;
  private final RecoveryCodes recovery;
  private final SecurityEvents events;
  private final WebAuthnJson json;
  private final Clock clock;
  private final AttemptGuard attempts;
  private final AccountLock accountLock;

  PasskeyController(PasskeyCeremonies ceremonies, PasskeyStore passkeys, AppUserRepository users, AuthSessions sessions,
      RecoveryCodes recovery, SecurityEvents events, WebAuthnJson json, Clock clock, AttemptGuard attempts,
      AccountLock accountLock) {
    this.ceremonies = ceremonies;
    this.passkeys = passkeys;
    this.users = users;
    this.sessions = sessions;
    this.recovery = recovery;
    this.events = events;
    this.json = json;
    this.clock = clock;
    this.attempts = attempts;
    this.accountLock = accountLock;
  }

  // --- Registro (P1, R, o F con verificación reciente) -------------------------------------------

  @PostMapping("/passkeys/registration/options")
  ResponseEntity<String> registrationOptions(Authentication authentication, HttpServletRequest request,
      HttpServletResponse response) {
    SessionUser user = AuthSessions.user(authentication);
    attempts.optionsIssued(request);
    // Bajo el bloqueo de la cuenta (H7): crea el identificador WebAuthn si aún no existe.
    return accountLock.forSession(request, response, user, "passkey.registration_options", () -> {
      requireSingleFirstEnrollment(authentication, user);
      AppUser account = activeAccount(user);
      Issued issued = ceremonies.registrationOptions(session(request).getId(), user.id(), account.email());
      return options(issued);
    });
  }

  @PostMapping(path = "/passkeys/registration", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<StateResponse> register(Authentication authentication, HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    SessionUser user = AuthSessions.user(authentication);
    JsonNode body = json.readBody(request.getInputStream());
    // Todo el registro, bajo el bloqueo de la cuenta (H7): una recuperación de emergencia no puede
    // intercalarse y dejar una passkey creada por una sesión anterior.
    return accountLock.forSession(request, response, user, "passkey.registration",
        () -> register(authentication, user, body, request, response));
  }

  private ResponseEntity<StateResponse> register(Authentication authentication, SessionUser user, JsonNode body,
      HttpServletRequest request, HttpServletResponse response) {
    requireSingleFirstEnrollment(authentication, user);
    State state = AuthStates.of(authentication);
    Credential credential;
    try {
      credential = ceremonies.register(session(request).getId(), user.id(), body);
    } catch (CeremonyFailure failure) {
      events.record("passkey.registration_failed", user.id(), Map.of("reason", failure.reason.name()));
      if (failure.reason == Reason.MALFORMED) {
        throw new IllegalArgumentException("registro mal formado");
      }
      throw new AuthFailure(HttpStatus.BAD_REQUEST, "registro de passkey no válido");
    }
    // Auditoría antes del cambio de estado (E2): si falla, la sesión sigue como estaba.
    events.record("passkey.registered", user.id(), Map.of("passkey", credential.id().toString()));
    Authentication next = authentication;
    if (state == State.PASSKEY_ENROLLMENT_REQUIRED || state == State.RECOVERY) {
      // Registrar no da F: hay que autenticarse con la passkey (P2), lo que demuestra que funciona.
      if (state == State.RECOVERY) {
        int invalidated = recovery.invalidateRemaining(user.id());
        events.record("recovery.completed", user.id(), Map.of("invalidatedCodes", Integer.toString(invalidated)));
      }
      next = sessions.switchTo(request, response, user,
          AuthSessions.authorities(authentication, a -> a.equals(STATE_ENROLL) || a.equals(FACTOR_RECOVERY), List.of()));
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(new StateResponse(AuthStates.of(next), null));
  }

  // --- Autenticación (P2 → F) ---------------------------------------------------------------------

  @PostMapping("/passkeys/authentication/options")
  ResponseEntity<String> authenticationOptions(Authentication authentication, HttpServletRequest request) {
    return assertionOptions(authentication, request, Ceremony.AUTHENTICATE);
  }

  @PostMapping(path = "/passkeys/authentication", consumes = MediaType.APPLICATION_JSON_VALUE)
  StateResponse authenticate(Authentication authentication, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    SessionUser user = AuthSessions.user(authentication);
    JsonNode body = json.readBody(request.getInputStream());
    // Todo el paso a F, bajo el bloqueo de la cuenta (H7): consumo del challenge, verificación,
    // contador, auditoría, dispositivo, fin del registro y sesión nueva.
    return accountLock.forSession(request, response, user, "passkey.authentication",
        () -> authenticate(authentication, user, body, request, response));
  }

  private StateResponse authenticate(Authentication authentication, SessionUser user, JsonNode body,
      HttpServletRequest request, HttpServletResponse response) {
    verify(authentication, request, response, Ceremony.AUTHENTICATE, FAILURE_PASSKEY, body);
    AppUser account = users.findById(user.id()).filter(AppUser::enabled).orElse(null);
    if (account == null) {
      sessions.close(request, response);
      throw new AuthFailure(HttpStatus.UNAUTHORIZED, "passkey no válida");
    }
    // Sin auditoría no hay acceso (E2): se escribe antes de conceder F.
    events.record("login.passkey_succeeded", user.id());
    // Dispositivo de confianza: rotación o emisión antes de conceder F (si falla, no hay acceso).
    var issued = attempts.fullAccess(request, user, account.email());
    if (account.enrollRequired()) {
      passkeys.completeEnrollment(user.id());
    }
    sessions.resetFailures(request, FAILURE_PASSKEY);
    Authentication full = sessions.switchTo(request, response, user, AuthSessions.authorities(authentication,
        a -> false, List.of(FactorGrantedAuthority.fromAuthority(AuthStates.FACTOR_WEBAUTHN),
            new SimpleGrantedAuthority("ROLE_" + account.role()))));
    session(request).setAttribute(AuthStates.WEBAUTHN_VERIFIED_AT, clock.instant());
    issued.ifPresent(i -> attempts.writeCookie(response, i));
    return new StateResponse(AuthStates.of(full), null);
  }

  // --- Step-up (F) --------------------------------------------------------------------------------

  @PostMapping("/step-up/options")
  ResponseEntity<String> stepUpOptions(Authentication authentication, HttpServletRequest request) {
    return assertionOptions(authentication, request, Ceremony.STEP_UP);
  }

  /** Renueva la verificación reciente. No cambia las autoridades ni el usuario: la sesión sigue en F. */
  @PostMapping(path = "/step-up", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> stepUp(Authentication authentication, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    SessionUser user = AuthSessions.user(authentication);
    JsonNode body = json.readBody(request.getInputStream());
    return accountLock.forSession(request, response, user, "step_up", () -> {
      verify(authentication, request, response, Ceremony.STEP_UP, FAILURE_STEP_UP, body);
      // Auditoría antes de renovar la verificación reciente (E2).
      events.record("step_up.succeeded", user.id());
      sessions.resetFailures(request, FAILURE_STEP_UP);
      sessions.rotate(request, response).setAttribute(AuthStates.WEBAUTHN_VERIFIED_AT, clock.instant());
      return ResponseEntity.<Void>noContent().build();
    });
  }

  // --- Gestión (F) --------------------------------------------------------------------------------

  @GetMapping("/passkeys")
  List<PasskeyView> list(Authentication authentication) {
    return passkeys.forUser(AuthSessions.user(authentication).id()).stream()
        .map(c -> new PasskeyView(c.id(), c.label(), c.createdAt(), c.lastUsedAt(), !c.usable(), c.backupEligible(),
            c.backupState(), c.transports()))
        .toList();
  }

  /** Borra una passkey propia (F con verificación reciente). Nunca la última utilizable. */
  @DeleteMapping("/passkeys/{id}")
  ResponseEntity<Void> delete(Authentication authentication, @PathVariable("id") UUID id) {
    SessionUser user = AuthSessions.user(authentication);
    return switch (passkeys.delete(user.id(), id)) {
      case DELETED -> {
        events.record("passkey.deleted", user.id(), Map.of("passkey", id.toString()));
        yield ResponseEntity.noContent().build();
      }
      case NOT_FOUND -> throw new AuthFailure(HttpStatus.NOT_FOUND, "passkey no encontrada");
      case LAST_USABLE -> throw new AuthFailure(HttpStatus.CONFLICT, "no se puede borrar la última passkey utilizable");
    };
  }

  // --- Auxiliares ---------------------------------------------------------------------------------

  private ResponseEntity<String> assertionOptions(Authentication authentication, HttpServletRequest request,
      Ceremony ceremony) {
    SessionUser user = AuthSessions.user(authentication);
    attempts.optionsIssued(request);
    Issued issued;
    try {
      issued = ceremonies.assertionOptions(session(request).getId(), user.id(), ceremony);
    } catch (CeremonyFailure failure) {
      throw new AuthFailure(HttpStatus.CONFLICT, "no hay ninguna passkey utilizable");
    }
    return options(issued);
  }

  /** Verifica la aserción; ante un fallo cuenta el intento y, al llegar al límite, cierra la sesión. */
  private Verified verify(Authentication authentication, HttpServletRequest request, HttpServletResponse response,
      Ceremony ceremony, String failureKind, JsonNode body) {
    SessionUser user = AuthSessions.user(authentication);
    try {
      return ceremonies.authenticate(session(request).getId(), user.id(), ceremony, body);
    } catch (CeremonyFailure failure) {
      if (failure.reason == Reason.MALFORMED) {
        throw new IllegalArgumentException("aserción mal formada");
      }
      // Contadores primero (confirmados) y auditoría después (E2).
      attempts.secondFactorFailure(request, user, ceremony.db + ".failed", Map.of("reason", failure.reason.name()));
      if (failure.reason == Reason.FOREIGN_CREDENTIAL && ceremony == Ceremony.AUTHENTICATE) {
        // Solo se ofrecen sus passkeys: una ajena al completar el acceso es una manipulación y se
        // cierra la sesión. En el step-up (sesión F) se rechaza y cuenta como fallo (diseño aprobado).
        sessions.close(request, response);
        throw new AuthFailure(HttpStatus.UNAUTHORIZED, "passkey no válida");
      }
      failed(request, response, user, failureKind);
      throw new AuthFailure(HttpStatus.UNAUTHORIZED, "passkey no válida");
    } catch (CounterFailure failure) {
      attempts.secondFactorFailure(request, user, "webauthn.counter_" + failure.result.classification().db,
          Map.of("passkey", failure.credential.id().toString(),
              "stored", Long.toString(failure.result.storedBefore()),
              "suspended", Boolean.toString(failure.result.suspended())));
      failed(request, response, user, failureKind);
      throw new AuthFailure(HttpStatus.UNAUTHORIZED, "passkey no válida");
    }
  }

  private void failed(HttpServletRequest request, HttpServletResponse response, SessionUser user, String kind) {
    if (sessions.recordFailure(request, response, kind)) {
      events.recordBestEffort("session.closed_after_failures", user.id(), Map.of("kind", kind));
    }
  }

  /** En P1 solo se registra la primera passkey; después hay que autenticarse con ella. */
  private void requireSingleFirstEnrollment(Authentication authentication, SessionUser user) {
    if (AuthStates.of(authentication) == State.PASSKEY_ENROLLMENT_REQUIRED && passkeys.count(user.id()) > 0) {
      throw new AuthFailure(HttpStatus.CONFLICT, "ya hay una passkey registrada: autentícate con ella");
    }
  }

  private AppUser activeAccount(SessionUser user) {
    return users.findById(user.id()).filter(AppUser::enabled)
        .orElseThrow(() -> new AuthFailure(HttpStatus.UNAUTHORIZED, "no autenticado"));
  }

  private static HttpSession session(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      throw new AuthFailure(HttpStatus.UNAUTHORIZED, "no autenticado");
    }
    return session;
  }

  private static ResponseEntity<String> options(Issued issued) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(issued.optionsJson());
  }
}
