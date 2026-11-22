package com.cristianatienza.cms.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Despacho de errores del contenedor (/error): sustituye al de Spring Boot para que también los
 * errores producidos fuera de los controladores (filtros, sendError) sean ProblemDetail genéricos,
 * con el mismo identificador de correlación. Nunca muestra la excepción ni su mensaje. Si la causa
 * es la base de datos no disponible (por ejemplo, Spring Session al cargar la sesión o el filtro del
 * sello), 503 «unavailable» con Retry-After (6.2.7.2, D2).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiErrorController implements ErrorController {

  private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

  @RequestMapping("/error")
  ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
    if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable error
        && ApiProblems.databaseUnavailable(error)) {
      log.warn("base de datos no disponible fuera de los controladores ({}): 503", error.getClass().getSimpleName());
      return ApiProblems.unavailable();
    }
    int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code ? code : 500;
    if (status < 400) {
      status = 500;
    }
    return ApiProblems.response(status, ApiProblems.defaultCode(status), ApiProblems.defaultDetail(status));
  }
}
