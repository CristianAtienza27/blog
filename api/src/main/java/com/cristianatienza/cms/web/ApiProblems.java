package com.cristianatienza.cms.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Errores de la API en ProblemDetail (RFC 9457, decisión G1): {@code type} about:blank (implícito), el
 * {@code title} del estado, un {@code detail} genérico, un {@code code} estable para el CMS y el
 * {@code correlationId}. Nunca trazas, clases, mensajes de excepción ni datos de la petición.
 */
public final class ApiProblems {

  public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ApiProblems() {}

  /** Código estable por defecto de cada estado. */
  public static String defaultCode(int status) {
    return switch (status) {
      case 400 -> "bad_request";
      case 401 -> "unauthenticated";
      case 403 -> "forbidden";
      case 404 -> "not_found";
      case 405 -> "method_not_allowed";
      case 409 -> "conflict";
      case 413 -> "payload_too_large";
      case 415 -> "unsupported_media_type";
      case 429 -> "too_many_requests";
      default -> status >= 500 ? "internal_error" : "error";
    };
  }

  /** Texto genérico por defecto de cada estado (sin detalles internos). */
  public static String defaultDetail(int status) {
    return switch (status) {
      case 400 -> "solicitud no válida";
      case 401 -> "no autenticado";
      case 403 -> "acceso denegado";
      case 404 -> "no encontrado";
      case 405 -> "método no permitido";
      case 409 -> "conflicto";
      case 413 -> "solicitud demasiado grande";
      case 415 -> "se esperaba JSON";
      case 429 -> "demasiadas solicitudes";
      default -> status >= 500 ? "error interno" : "error";
    };
  }

  /** Segundos de Retry-After de un 503 «unavailable». */
  public static final int UNAVAILABLE_RETRY_SECONDS = 5;

  /**
   * ¿La causa es que la base de datos no está disponible (6.2.7.2, D2)? Sin conexión libre en el
   * pool en connection-timeout, conexión rota o caída, o una consulta que agota socketTimeout. Recorre
   * las causas: la auditoría, las transacciones y Spring Session envuelven el error original.
   */
  public static boolean databaseUnavailable(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof org.springframework.dao.DataAccessResourceFailureException
          || t instanceof org.springframework.transaction.CannotCreateTransactionException
          || t instanceof java.sql.SQLTransientConnectionException
          || t instanceof java.sql.SQLNonTransientConnectionException) {
        return true;
      }
      // Clase 08 (excepción de conexión) y 57P0x (el servidor se está deteniendo o no admite conexiones).
      if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null
          && (sql.getSQLState().startsWith("08") || sql.getSQLState().startsWith("57P0"))) {
        return true;
      }
      // Fallo al confirmar o deshacer porque la conexión se perdió (6.2.7.4, reinicio de PostgreSQL
      // al guardar la sesión): Hikari lanza «Connection is closed» SIN SQLSTATE. Solo dentro de un
      // fallo de transacción, para no confundirlo con otros errores del cliente.
      if (t instanceof org.springframework.transaction.TransactionSystemException) {
        for (Throwable c = t.getCause(); c != null; c = c.getCause() == c ? null : c.getCause()) {
          if (c instanceof java.sql.SQLException sql
              && (sql.getSQLState() == null || sql.getSQLState().startsWith("08"))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** 503 rápido cuando la base de datos no está disponible: código estable y Retry-After. */
  public static ResponseEntity<ProblemDetail> unavailable() {
    ResponseEntity<ProblemDetail> problem = response(503, "unavailable",
        "servicio no disponible temporalmente; inténtalo de nuevo en unos segundos");
    return ResponseEntity.status(problem.getStatusCode()).headers(problem.getHeaders())
        .header("Retry-After", Integer.toString(UNAVAILABLE_RETRY_SECONDS)).body(problem.getBody());
  }

  /** El mismo 503, escrito directamente (fuera de Spring MVC: cadena de filtros). */
  public static void writeUnavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setHeader("Retry-After", Integer.toString(UNAVAILABLE_RETRY_SECONDS));
    write(request, response, 503, "unavailable", "servicio no disponible temporalmente; inténtalo de nuevo en unos segundos");
  }

  public static ProblemDetail problem(int status, String code, String detail, String correlationId) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
    problem.setProperty("code", code);
    problem.setProperty("correlationId", correlationId);
    return problem;
  }

  /** Respuesta de un controlador o de un manejador de excepciones. */
  public static ResponseEntity<ProblemDetail> response(int status, String code, String detail) {
    return ResponseEntity.status(status).contentType(PROBLEM_JSON)
        .body(problem(status, code, detail, ApiResponseFilter.correlationId(currentRequest())));
  }

  /** Escritura directa (filtros de Spring Security, cortafuegos), fuera de Spring MVC. */
  public static void write(HttpServletRequest request, HttpServletResponse response, int status, String code,
      String detail) throws IOException {
    if (response.isCommitted()) {
      return;
    }
    String correlationId = ApiResponseFilter.correlationId(request);
    response.setStatus(status);
    ApiResponseFilter.applyHeaders(request, response, correlationId == null ? "" : correlationId);
    response.setContentType(PROBLEM_JSON.toString());
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    Map<String, Object> body = new LinkedHashMap<>();
    // Sin «type»: en RFC 9457 su ausencia equivale a about:blank (igual que la serialización de Spring).
    body.put("title", HttpStatus.valueOf(status).getReasonPhrase());
    body.put("status", status);
    body.put("detail", detail);
    body.put("code", code);
    body.put("correlationId", correlationId);
    String json = JSON.writeValueAsString(body);
    try {
      response.getWriter().write(json);
    } catch (IllegalStateException outputStreamInUse) {
      // Ya se abrió el flujo binario (p. ej., Spring MVC empezó a escribir otra respuesta y falló al
      // confirmarla, 6.2.7.2): se escribe por él. Tras resetBuffer, sin restos de la anterior.
      response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static HttpServletRequest currentRequest() {
    return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
        ? attributes.getRequest() : null;
  }
}
