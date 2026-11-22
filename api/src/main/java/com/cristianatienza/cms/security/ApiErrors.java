package com.cristianatienza.cms.security;

import com.cristianatienza.cms.security.PasswordHashing.HashingSaturatedException;
import com.cristianatienza.cms.web.ApiProblems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Errores de toda la API en ProblemDetail (RFC 9457, decisión G1), con un código estable y el
 * identificador de correlación. Ninguna respuesta revela información interna: ni trazas, ni clases,
 * ni mensajes de excepción, ni datos de la petición. Lo inesperado es un 500 genérico; su traza solo
 * va a los registros del servidor, con el identificador de correlación.
 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiErrors {

  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  /** Mismo mensaje para contraseña incorrecta, usuario inexistente o desactivado. */
  public static class LoginFailedException extends RuntimeException {
    LoginFailedException() {
      super("credenciales no válidas", null, false, false);
    }
  }

  /** Rechazo de una operación de autenticación con un estado, un mensaje genérico y un código. */
  public static class AuthFailure extends RuntimeException {
    final HttpStatus status;
    final String code;

    public AuthFailure(HttpStatus status, String message) {
      this(status, ApiProblems.defaultCode(status.value()), message);
    }

    public AuthFailure(HttpStatus status, String code, String message) {
      super(message, null, false, false);
      this.status = status;
      this.code = code;
    }
  }

  /** Demasiados intentos (6.2.4): 429 con Retry-After y un mensaje genérico. */
  public static class RateLimitedException extends RuntimeException {
    final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
      super("demasiados intentos", null, false, false);
      this.retryAfterSeconds = retryAfterSeconds;
    }
  }

  @ExceptionHandler(RateLimitedException.class)
  ResponseEntity<ProblemDetail> rateLimited(RateLimitedException e) {
    ResponseEntity<ProblemDetail> problem = ApiProblems.response(429, "rate_limited", "demasiados intentos; inténtalo más tarde");
    return ResponseEntity.status(problem.getStatusCode()).headers(problem.getHeaders())
        .header("Retry-After", Long.toString(e.retryAfterSeconds)).body(problem.getBody());
  }

  @ExceptionHandler(AuthFailure.class)
  ResponseEntity<ProblemDetail> authFailure(AuthFailure failure) {
    return ApiProblems.response(failure.status.value(), failure.code, failure.getMessage());
  }

  @ExceptionHandler(LoginFailedException.class)
  ResponseEntity<ProblemDetail> loginFailed() {
    return ApiProblems.response(401, "invalid_credentials", "credenciales no válidas");
  }

  /** Saturación de Argon2id: comportamiento esperado, no un fallo. */
  @ExceptionHandler(HashingSaturatedException.class)
  ResponseEntity<ProblemDetail> saturated() {
    ResponseEntity<ProblemDetail> problem = ApiProblems.response(429, "saturated",
        "demasiadas solicitudes; inténtalo de nuevo en un momento");
    return ResponseEntity.status(problem.getStatusCode()).headers(problem.getHeaders()).header("Retry-After", "1")
        .body(problem.getBody());
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class,
      MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
  ResponseEntity<ProblemDetail> badRequest() {
    return ApiProblems.response(400, "bad_request", "solicitud no válida");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> unsupported() {
    return ApiProblems.response(415, "unsupported_media_type", "se esperaba JSON");
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<ProblemDetail> notAcceptable() {
    return ApiProblems.response(406, "not_acceptable", "formato de respuesta no disponible");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ProblemDetail> methodNotAllowed() {
    return ApiProblems.response(405, "method_not_allowed", "método no permitido");
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ProblemDetail> notFound() {
    return ApiProblems.response(404, "not_found", "no encontrado");
  }

  /** Denegación de la seguridad de métodos (@CmsEditor, @CmsAdmin, @RecentPasskey). */
  @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
  ResponseEntity<ProblemDetail> accessDenied() {
    return ApiProblems.response(403, "forbidden", "acceso denegado");
  }

  /**
   * Cualquier otra cosa: 500 genérico, salvo que la causa sea la base de datos no disponible (pool
   * agotado, conexión rota o caída): entonces 503 «unavailable» con Retry-After (6.2.7.2, D2), en
   * como mucho connection-timeout (5 s). La traza, solo en el registro del servidor.
   */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception e) {
    if (ApiProblems.databaseUnavailable(e)) {
      log.warn("base de datos no disponible ({}): 503", e.getClass().getSimpleName());
      return ApiProblems.unavailable();
    }
    log.error("error interno no controlado ({})", e.getClass().getSimpleName(), e);
    return ApiProblems.response(500, "internal_error", "error interno");
  }
}
