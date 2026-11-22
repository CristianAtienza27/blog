package com.cristianatienza.cms.security;

import com.cristianatienza.cms.security.AuthStates.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

/**
 * Transiciones de la sesión (máquina de estados de api/docs/seguridad.md).
 *
 * <p><b>Rotación por migración.</b> Cada cambio de estado o de verificación reciente crea una
 * sesión NUEVA (otra fila, otro PRIMARY_ID), copia sus atributos y borra la anterior. No se usa
 * {@code changeSessionId()}: Spring Session JDBC conserva el PRIMARY_ID y cada petición guarda la
 * fila con el identificador que cargó, así que una petición simultánea que terminara después podía
 * devolver la sesión al identificador anterior (comprobado en la 6.2.3). Con la migración, una
 * petición tardía actualiza una fila que ya no existe: no puede resucitar ni sobrescribir nada.
 *
 * <p><b>Fallos por sesión (D7)</b> en auth_session_failure (V8), con un incremento atómico en
 * PostgreSQL: las peticiones que fallan no escriben en la sesión.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthSessions {

  private final SecurityContextRepository contexts;
  private final CookieCsrfTokenRepository csrfTokens;
  private final FindByIndexNameSessionRepository<?> sessions;
  private final SecretKeys keys;
  private final JdbcTemplate jdbc;
  private final int maxFailures;
  private final Duration failureTtl;

  public AuthSessions(SecurityContextRepository contexts, CookieCsrfTokenRepository csrfTokens,
      FindByIndexNameSessionRepository<?> sessions, SecretKeys keys, DataSource dataSource,
      @Value("${cms.security.webauthn.max-failures-per-session:5}") int maxFailures,
      @Value("${cms.security.session.absolute-timeout:12h}") Duration absoluteTimeout) {
    this.contexts = contexts;
    this.csrfTokens = csrfTokens;
    this.sessions = sessions;
    this.keys = keys;
    this.jdbc = new JdbcTemplate(dataSource);
    this.maxFailures = maxFailures;
    // Un contador no sobrevive a su sesión: como mucho, la caducidad absoluta.
    this.failureTtl = absoluteTimeout;
  }

  public static SessionUser user(Authentication authentication) {
    return (SessionUser) authentication.getPrincipal();
  }

  /** Autoridades de la autenticación actual, sin las que cumplan {@code drop}, más {@code add}. */
  public static List<GrantedAuthority> authorities(Authentication current, Predicate<String> drop,
      Collection<? extends GrantedAuthority> add) {
    List<GrantedAuthority> out = new ArrayList<>();
    for (GrantedAuthority a : current.getAuthorities()) {
      if (!drop.test(a.getAuthority())) {
        out.add(a);
      }
    }
    out.addAll(add);
    return out;
  }

  /**
   * Sesión nueva con los atributos de la actual (si la hay), que se borra. Devuelve la nueva.
   * Contra la fijación de sesión y contra las peticiones simultáneas con el identificador anterior.
   */
  public HttpSession renew(HttpServletRequest request) {
    HttpSession old = request.getSession(false);
    Map<String, Object> attributes = new LinkedHashMap<>();
    if (old != null) {
      for (String name : Collections.list(old.getAttributeNames())) {
        attributes.put(name, old.getAttribute(name));
      }
      deleteFailures(old.getId());
      old.invalidate();
    }
    HttpSession fresh = request.getSession(true);
    attributes.forEach(fresh::setAttribute);
    return fresh;
  }

  /** Cambia de estado: autenticación nueva, sesión nueva y token CSRF nuevo. */
  public Authentication switchTo(HttpServletRequest request, HttpServletResponse response, SessionUser user,
      List<GrantedAuthority> authorities) {
    Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
    renew(request);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    rotateCsrf(request, response);
    return authentication;
  }

  /** Mismo estado, sesión y token nuevos (verificación reciente renovada). */
  public HttpSession rotate(HttpServletRequest request, HttpServletResponse response) {
    HttpSession fresh = renew(request);
    contexts.saveContext(SecurityContextHolder.getContext(), request, response);
    rotateCsrf(request, response);
    return fresh;
  }

  /** Token CSRF nuevo tras cambiar el estado de autenticación. */
  public void rotateCsrf(HttpServletRequest request, HttpServletResponse response) {
    csrfTokens.saveToken(null, request, response);
    csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
  }

  /**
   * Cuenta un fallo de la sesión (D7) con un incremento atómico en PostgreSQL. Al llegar al límite,
   * la sesión se cierra y devuelve true. No escribe en la sesión.
   */
  public boolean recordFailure(HttpServletRequest request, HttpServletResponse response, String kind) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return true;
    }
    Integer failures = jdbc.queryForObject("INSERT INTO auth_session_failure (session_hash, kind, failures, expires_at)"
        + " VALUES (?, ?, 1, now() + ? * interval '1 millisecond')"
        + " ON CONFLICT (session_hash, kind) DO UPDATE SET failures = auth_session_failure.failures + 1"
        + " RETURNING failures", Integer.class, keys.sessionHash(session.getId()), kind, failureTtl.toMillis());
    if (failures != null && failures >= maxFailures) {
      close(request, response);
      return true;
    }
    return false;
  }

  public void resetFailures(HttpServletRequest request, String kind) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      jdbc.update("DELETE FROM auth_session_failure WHERE session_hash = ? AND kind = ?",
          keys.sessionHash(session.getId()), kind);
    }
  }

  /** Cierra la sesión actual. */
  public void close(HttpServletRequest request, HttpServletResponse response) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      deleteFailures(session.getId());
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
    rotateCsrf(request, response);
  }

  /** Cierra todas las demás sesiones del usuario (canje de un código de recuperación). */
  public int closeOtherSessions(SessionUser user, String currentSessionId) {
    int closed = 0;
    for (String id : sessions.findByPrincipalName(user.getName()).keySet()) {
      if (!id.equals(currentSessionId)) {
        sessions.deleteById(id);
        closed++;
      }
    }
    return closed;
  }

  /** Cierra todas las sesiones del usuario, también la actual. */
  public void closeAllSessions(SessionUser user) {
    sessions.findByPrincipalName(user.getName()).keySet().forEach(sessions::deleteById);
  }

  private void deleteFailures(String sessionId) {
    jdbc.update("DELETE FROM auth_session_failure WHERE session_hash = ?", keys.sessionHash(sessionId));
  }

  /** Limpieza de contadores de sesiones ya caducadas (idempotente con varias instancias). */
  @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT5M")
  void purgeExpiredFailures() {
    jdbc.update("DELETE FROM auth_session_failure WHERE expires_at <= now()");
  }
}
