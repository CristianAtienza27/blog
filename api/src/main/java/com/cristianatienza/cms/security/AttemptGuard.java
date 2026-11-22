package com.cristianatienza.cms.security;

import com.cristianatienza.cms.security.ApiErrors.RateLimitedException;
import com.cristianatienza.cms.security.AuthStates.SessionUser;
import com.cristianatienza.cms.security.devices.TrustedDevices;
import com.cristianatienza.cms.security.devices.TrustedDevices.Issued;
import com.cristianatienza.cms.security.devices.TrustedDevices.Presentation;
import com.cristianatienza.cms.security.devices.TrustedDevices.Trust;
import com.cristianatienza.cms.security.limits.LoginLimiter;
import com.cristianatienza.cms.security.limits.LoginLimiter.Decision;
import com.cristianatienza.cms.security.limits.LoginLimiter.FailureResult;
import com.cristianatienza.cms.security.limits.LoginLimiter.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Limitación de intentos y dispositivos de confianza en el flujo de autenticación (fase 6.2.4).
 *
 * <p>Orden de persistencia (E2): ante un fallo, primero los contadores (confirmados en su propia
 * transacción) y las reglas del dispositivo; después la auditoría. Un fallo posterior de la
 * auditoría no deshace el contador. Ante un acceso completo, la auditoría ya se ha escrito antes
 * (quien llama la escribe antes de conceder nada); después se rota el dispositivo y se reinician
 * los contadores de la pareja.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AttemptGuard {

  /** Confianza del dispositivo evaluada al iniciar sesión (no es un factor). */
  public static final String DEVICE_FAMILY = "cms.auth.deviceFamily";
  public static final String DEVICE_PRESENTATION = "cms.auth.devicePresentation";

  private final LoginLimiter limiter;
  private final TrustedDevices devices;
  private final AppUserRepository users;
  private final SecurityEvents events;
  private final SecretKeys keys;
  private final Clock clock;

  public AttemptGuard(LoginLimiter limiter, TrustedDevices devices, AppUserRepository users, SecurityEvents events,
      SecretKeys keys, Clock clock) {
    this.limiter = limiter;
    this.devices = devices;
    this.users = users;
    this.events = events;
    this.keys = keys;
    this.clock = clock;
  }

  public static String ip(HttpServletRequest request) {
    return request.getRemoteAddr();
  }

  // --- Inicio de sesión con contraseña --------------------------------------------------------

  /** Evalúa el dispositivo presentado (solo si el usuario existe) y devuelve su confianza. */
  public Trust evaluateDevice(HttpServletRequest request, UUID userId) {
    return devices.evaluate(deviceCookie(request), userId, ip(request), request.getHeader("User-Agent"));
  }

  public Keys keys(HttpServletRequest request, String typedEmail, Trust trust) {
    return limiter.keys(ip(request), typedEmail, trust.trustedFamily());
  }

  /** Comprobación previa: si está bloqueado, audita y responde 429 (sin calcular Argon2id). */
  public void check(Keys k, UUID userId) {
    Decision decision = limiter.check(k);
    if (!decision.allowed()) {
      events.record("limits.login_blocked", userId, Map.of("scope", decision.scope()));
      throw new RateLimitedException(decision.retryAfterSeconds());
    }
  }

  /**
   * Un fallo: contadores y reglas del dispositivo (confirmados), después la auditoría. Si la
   * auditoría falla, la excepción se propaga (500) con el contador ya incrementado.
   */
  public void failure(Keys k, Trust trust, UUID userId, String event, Map<String, String> details) {
    FailureResult result = limiter.recordFailure(k);
    if (userId != null && trust.familyId() != null) {
      if (trust.trusted() && result.deviceDayExceeded()) {
        devices.revokeFamily(userId, trust.familyId(), "device_failures");
      } else if (trust.stale() && result.blockedNow()) {
        devices.revokeFamily(userId, trust.familyId(), "stale_failures");
      }
    }
    if (result.attackModeEntered()) {
      events.recordBestEffort("limits.attack_mode_entered", userId);
    }
    events.record(event, userId, details);
  }

  /** HMAC del correo tecleado, para auditar un usuario inexistente sin guardar lo que se tecleó. */
  public String typedEmailHmac(String typedEmail) {
    return HexFormat.of().formatHex(keys.attemptKey("account", LoginLimiter.normalizeEmail(typedEmail)));
  }

  /** Guarda en la sesión nueva la confianza evaluada al iniciar sesión. */
  public static void remember(HttpSession session, Trust trust) {
    if (trust.familyId() != null) {
      session.setAttribute(DEVICE_FAMILY, trust.familyId());
    }
    session.setAttribute(DEVICE_PRESENTATION, trust.presentation().name());
  }

  // --- Segundo factor (P2, R, step-up) -----------------------------------------------------------

  /** La confianza guardada al iniciar sesión (sin volver a presentar el token). */
  public static Trust trustOf(HttpSession session) {
    if (session == null) {
      return new Trust(Presentation.NONE, null, null);
    }
    Object family = session.getAttribute(DEVICE_FAMILY);
    Object presentation = session.getAttribute(DEVICE_PRESENTATION);
    return new Trust(presentation instanceof String p ? Presentation.valueOf(p) : Presentation.NONE,
        family instanceof UUID f ? f : null, null);
  }

  /** Comprobación de la IP al emitir opciones WebAuthn, que además cuentan en su ámbito (E1). */
  public void optionsIssued(HttpServletRequest request) {
    Decision decision = limiter.checkIp(ip(request));
    if (!decision.allowed()) {
      throw new RateLimitedException(decision.retryAfterSeconds());
    }
    limiter.recordOptionsIssued(ip(request));
  }

  /** Fallo del segundo factor: cuenta como fallo de la cuenta (E1), con las mismas claves. */
  public void secondFactorFailure(HttpServletRequest request, SessionUser user, String event,
      Map<String, String> details) {
    String email = users.findById(user.id()).map(AppUserRepository.AppUser::email).orElse("");
    Trust trust = trustOf(request.getSession(false));
    failure(limiter.keys(ip(request), email, trust.trustedFamily()), trust, user.id(), event, details);
  }

  /**
   * Acceso completo (F), ya auditado: rota o emite el dispositivo (antes de conceder F: si falla,
   * no hay acceso), reinicia la pareja y el dispositivo, y prepara la cookie.
   */
  public Optional<Issued> fullAccess(HttpServletRequest request, SessionUser user, String email) {
    Trust trust = trustOf(request.getSession(false));
    Optional<Issued> issued = devices.onFullAccess(trust, user.id(), ip(request), request.getHeader("User-Agent"));
    limiter.resetOnFullAccess(limiter.keys(ip(request), email, trust.trustedFamily()));
    // La sesión recuerda la familia del token que se acaba de emitir (dispositivo «actual»).
    HttpSession session = request.getSession(false);
    if (session != null) {
      issued.ifPresent(i -> session.setAttribute(DEVICE_FAMILY, i.familyId()));
    }
    return issued;
  }

  /** Cookie __Host-DEVICE: HttpOnly, Secure, SameSite=Strict, Path=/, sin Domain. */
  public void writeCookie(HttpServletResponse response, Issued issued) {
    long maxAge = Math.max(0, Duration.between(clock.instant(), issued.expiresAt()).toSeconds());
    response.addHeader("Set-Cookie", ResponseCookie.from(TrustedDevices.COOKIE, issued.token())
        .httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(maxAge).build().toString());
  }

  private static String deviceCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (TrustedDevices.COOKIE.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
