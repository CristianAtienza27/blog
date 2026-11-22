package com.cristianatienza.cms.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.GenericFilterBean;

/**
 * Primer filtro de la API (fase 6.2.5), antes que Spring Security y en TODOS los despachos
 * (petición, error, asíncrono): identificador de correlación y cabeceras de seguridad. Así las
 * llevan también los rechazos del cortafuegos, los 401/403 de los filtros y el despacho de errores,
 * no solo las respuestas de los controladores. Las cabeceras propias de Spring Security están
 * desactivadas para no duplicarlas.
 *
 * <ul>
 *   <li><b>Correlación (G2):</b> lo genera la API (UUID) en cada petición; no se confía en ningún
 *       identificador recibido hasta tener un proxy de confianza (6.9). Va en {@code X-Correlation-Id},
 *       en los errores, en los registros (MDC) y en la auditoría.
 *   <li><b>Cabeceras:</b> las de las respuestas JSON de la API. El documento HTML del CMS tiene su
 *       propia política, que pone Caddy (6.4 y 6.9).
 *   <li><b>HSTS (G4):</b> solo con una petición HTTPS real ({@code request.isSecure()});
 *       X-Forwarded-Proto no se acepta (forward-headers-strategy: none). Un año, sin
 *       includeSubDomains (hasta comprobar los subdominios) y sin preload.
 * </ul>
 */
public class ApiResponseFilter extends GenericFilterBean {

  public static final String CORRELATION_HEADER = "X-Correlation-Id";
  /** Atributo de la petición y clave del MDC con el identificador de correlación. */
  public static final String CORRELATION_ATTRIBUTE = "cms.correlationId";
  public static final String MDC_KEY = "correlationId";

  static final String CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
  static final String PERMISSIONS_POLICY = "accelerometer=(), camera=(), geolocation=(), gyroscope=(), "
      + "magnetometer=(), microphone=(), payment=(), usb=()";
  static final String HSTS = "max-age=31536000";

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    // El mismo identificador en todos los despachos de una petición (petición y error).
    String correlationId = request.getAttribute(CORRELATION_ATTRIBUTE) instanceof String id ? id : UUID.randomUUID().toString();
    request.setAttribute(CORRELATION_ATTRIBUTE, correlationId);
    String previous = MDC.get(MDC_KEY);
    MDC.put(MDC_KEY, correlationId);
    try {
      applyHeaders(request, response, correlationId);
      chain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException e) {
      // Base de datos no disponible fuera de los controladores (6.2.7.2): Spring Session al cargar o
      // guardar la sesión, el filtro del sello… El despacho de errores no sirve aquí, porque vuelve a
      // cargar la sesión, vuelve a fallar y Tomcat respondería con su página HTML 500 (sin
      // ProblemDetail ni cabeceras). Se responde 503 directamente, antes que Spring Session.
      if (!ApiProblems.databaseUnavailable(e) || response.isCommitted()) {
        throw e;
      }
      logger.warn("base de datos no disponible en la cadena de filtros (" + e.getClass().getSimpleName() + "): 503");
      response.resetBuffer();
      ApiProblems.writeUnavailable(request, response);
    } finally {
      // Hilos virtuales: el MDC se restaura siempre, sin fugas entre peticiones.
      if (previous == null) {
        MDC.remove(MDC_KEY);
      } else {
        MDC.put(MDC_KEY, previous);
      }
    }
  }

  /** Cabeceras de seguridad de la API (idempotente: también tras un reset de la respuesta). */
  public static void applyHeaders(HttpServletRequest request, HttpServletResponse response, String correlationId) {
    if (response.isCommitted()) {
      return;
    }
    response.setHeader(CORRELATION_HEADER, correlationId);
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
    response.setHeader("Content-Security-Policy", CSP);
    response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
    if (request.isSecure()) {
      response.setHeader("Strict-Transport-Security", HSTS);
    }
  }

  /** El identificador de correlación de la petición (o null fuera de una petición). */
  public static String correlationId(HttpServletRequest request) {
    return request != null && request.getAttribute(CORRELATION_ATTRIBUTE) instanceof String id ? id : null;
  }
}
