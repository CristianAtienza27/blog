package com.cristianatienza.cms.content.publication;

import com.cristianatienza.cms.content.publication.PublicationRepository.Row;
import com.cristianatienza.cms.security.AuthSessions;
import com.cristianatienza.cms.security.policy.CmsAdmin;
import com.cristianatienza.cms.security.policy.RecentPasskey;
import com.cristianatienza.cms.web.ApiProblems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Publicaciones (fase 6.3.4). Publicar y activar: ADMIN con passkey reciente y CSRF; el listado,
 * solo ADMIN (lee). Las respuestas nunca llevan contenido: versiones, estados, huellas e incidencias.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/cms/publicaciones")
class PublicationController {

  private static final Logger log = LoggerFactory.getLogger(PublicationController.class);
  private static final Pattern VERSION = Pattern.compile("v([1-9][0-9]{0,8})");

  private final PublicationService service;

  PublicationController(PublicationService service) {
    this.service = service;
  }

  @PostMapping
  @CmsAdmin
  @RecentPasskey
  ResponseEntity<Map<String, Object>> publish(Authentication authentication) {
    PublicationService.Outcome outcome = service.publish(Actor.api(AuthSessions.user(authentication).id()));
    return ResponseEntity.status(switch (outcome.result()) {
      case PublicationService.RESULT_ACTIVATED -> 201;
      case PublicationService.RESULT_UNCHANGED -> 200;
      case PublicationService.RESULT_REJECTED -> 422;
      default -> 503;
    }).body(view(outcome));
  }

  @PostMapping("/{version}/activar")
  @CmsAdmin
  @RecentPasskey
  ResponseEntity<Map<String, Object>> activate(Authentication authentication, @PathVariable("version") String version) {
    Matcher matcher = VERSION.matcher(version);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("versión no válida");
    }
    PublicationService.Outcome outcome = service.activate(Integer.parseInt(matcher.group(1)),
        Actor.api(AuthSessions.user(authentication).id()));
    return ResponseEntity.status(switch (outcome.result()) {
      case PublicationService.RESULT_ACTIVATED, PublicationService.RESULT_UNCHANGED -> 200;
      case PublicationService.RESULT_REJECTED -> 422;
      default -> 503;
    }).body(view(outcome));
  }

  @GetMapping
  @CmsAdmin
  Map<String, Object> overview() {
    PublicationService.Overview overview = service.overview();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("configurada", overview.configured());
    if (!overview.configured()) {
      return out;
    }
    Map<String, Object> reconciliation = new LinkedHashMap<>();
    reconciliation.put("estado", overview.reconciliation());
    reconciliation.put("motivo", overview.reason());
    reconciliation.put("punteroVersion", overview.pointerVersion() == null ? null : "v" + overview.pointerVersion());
    reconciliation.put("comprobadaEn", overview.checkedAt());
    out.put("conciliacion", reconciliation);
    out.put("epoca", overview.epoch());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Row row : overview.publications()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("version", "v" + row.version());
      item.put("estado", row.state());
      item.put("contentSha256", row.contentSha256());
      item.put("archivos", row.files());
      item.put("bytes", row.bytes());
      item.put("articulos", row.articles());
      item.put("incidencias", issues(row.issues()));
      item.put("anterior", row.previousVersion() == null ? null : "v" + row.previousVersion());
      item.put("pedidaPor", row.requestedBy());
      item.put("via", row.requestedVia());
      item.put("creadaEn", row.createdAt());
      item.put("generadaEn", row.generatedAt());
      item.put("activadaEn", row.activatedAt());
      item.put("archivosPurgadosEn", row.filesPurgedAt());
      rows.add(item);
    }
    out.put("publicaciones", rows);
    return out;
  }

  @ExceptionHandler(PublicationFailure.class)
  ResponseEntity<ProblemDetail> failure(PublicationFailure failure) {
    if (failure.kind() == PublicationFailure.Kind.DATABASE || failure.kind() == PublicationFailure.Kind.INTERNAL) {
      log.error("Publicación: {}", failure.getMessage(), failure.getCause());
    } else {
      log.warn("Publicación: {}", failure.getMessage());
    }
    if (failure.kind() == PublicationFailure.Kind.DATABASE && ApiProblems.databaseUnavailable(failure)) {
      return ApiProblems.unavailable();
    }
    return ApiProblems.response(failure.kind().status, failure.kind().code, failure.getMessage());
  }

  private static Map<String, Object> view(PublicationService.Outcome outcome) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("resultado", outcome.result());
    out.put("version", outcome.version() == null ? null : "v" + outcome.version());
    out.put("estado", outcome.state());
    out.put("contentSha256", outcome.contentSha256());
    out.put("anterior", outcome.previous() == null ? null : "v" + outcome.previous());
    out.put("incidencias", outcome.issues());
    return out;
  }

  private static List<String> issues(String json) {
    List<String> out = new ArrayList<>();
    if (json == null) {
      return out;
    }
    JsonNode node = PublicationRepository.JSON.readTree(json);
    for (JsonNode issue : node.values()) {
      out.add(issue.asString(""));
    }
    return out;
  }
}
