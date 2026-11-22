package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.web.ApiProblems;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errores de los controladores editoriales (fase 6.4): ProblemDetail con {@code code},
 * {@code correlationId} (los de ApiProblems, 6.2.5) y {@code errors} cuando hay incidencias por campo.
 * Solo se aplica a este paquete; el resto de errores siguen en ApiErrors.
 */
@RestControllerAdvice(basePackageClasses = EditorialErrors.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Order(Ordered.HIGHEST_PRECEDENCE)
class EditorialErrors {

  @ExceptionHandler(EditorialException.class)
  ResponseEntity<ProblemDetail> editorial(EditorialException e) {
    ResponseEntity<ProblemDetail> response = ApiProblems.response(e.status(), e.code(), e.getMessage());
    if (!e.errors().isEmpty()) {
      List<Map<String, String>> errors = e.errors().stream()
          .map(error -> Map.of("path", error.path(), "message", error.message()))
          .toList();
      response.getBody().setProperty("errors", errors);
    }
    e.properties().forEach(response.getBody()::setProperty);
    return response;
  }
}
