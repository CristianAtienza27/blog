package com.cristianatienza.cms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caducidad absoluta de la sesión (12 h desde la autenticación con contraseña), además de la de
 * inactividad (30 min, Spring Session). Spring Session no la ofrece: se invalida la sesión y la
 * petición continúa como anónima (401 donde haga falta sesión). El reloj es inyectable (tests).
 * No es un componente: lo registra SecurityConfig en la cadena de seguridad, no como filtro global.
 */
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

  private final Clock clock;
  private final Duration absoluteTimeout;
  private final SecurityEvents events;

  public AbsoluteSessionTimeoutFilter(Clock clock, Duration absoluteTimeout, SecurityEvents events) {
    this.clock = clock;
    this.absoluteTimeout = absoluteTimeout;
    this.events = events;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    if (session != null && session.getAttribute(AuthStates.AUTHENTICATED_AT) instanceof Instant at
        && !clock.instant().isBefore(at.plus(absoluteTimeout))) {
      session.invalidate();
      // Acción protectora ya hecha: la auditoría no puede impedirla ni deshacerla (E2).
      events.recordBestEffort("session.absolute_expired", null);
      SecurityContextHolder.clearContext();
    }
    chain.doFilter(request, response);
  }
}
