package com.cristianatienza.cms.security;

import com.cristianatienza.cms.security.AuthStates.SessionUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sello de seguridad en cada petición con sesión (H7, fase 6.2.6): la sesión guarda el
 * {@code password_changed_at} del usuario leído al iniciar sesión; si ya no es el actual (la
 * recuperación de emergencia lo cambia), la sesión se cierra y la petición continúa como anónima
 * (401 donde haga falta sesión). Cubre la sesión que Spring Session inserta al confirmar la
 * respuesta, fuera del bloqueo por usuario ({@link AccountLock}): si eso ocurre después de una
 * recuperación, lleva el sello anterior y se cierra en su primera petición. Una sesión sin sello
 * (anterior a la 6.2.6) también se cierra. Coste: una lectura por clave primaria por petición.
 * No es un componente: lo registra SecurityConfig en la cadena de seguridad.
 */
public class SecurityStampFilter extends OncePerRequestFilter {

  private final AppUserRepository users;
  private final SecurityEvents events;

  public SecurityStampFilter(AppUserRepository users, SecurityEvents events) {
    this.users = users;
    this.events = events;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    Authentication authentication = session == null ? null : SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof SessionUser user) {
      Optional<Instant> current = users.securityStamp(user.id());
      Object stamp = session.getAttribute(AuthStates.SECURITY_STAMP);
      if (current.isEmpty() || !(stamp instanceof Instant own) || !own.equals(current.get())) {
        session.invalidate();
        SecurityContextHolder.clearContext();
        // Acción protectora ya hecha: la auditoría no puede impedirla ni deshacerla (E2).
        events.recordBestEffort("session.stale_stamp", user.id());
      }
    }
    chain.doFilter(request, response);
  }
}
