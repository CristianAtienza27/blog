package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.CatalogService.Detail;
import com.cristianatienza.cms.content.editorial.CatalogService.Kind;
import com.cristianatienza.cms.content.editorial.EditorialException.FieldError;
import com.cristianatienza.cms.security.policy.CmsAdmin;
import com.cristianatienza.cms.security.policy.CmsEditor;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Autores, lugares, itinerarios y proyectos (fase 6.4.7), con CSRF. Las listas siguen en
 * CatalogController (6.4.2). Para cada colección:
 *
 * <pre>
 *   GET    /api/cms/{colección}/{id}   → {record, version, usage}                      (ADMIN o EDITOR)
 *   POST   /api/cms/{colección}        {registro del contrato, sin id}  → 201          (ADMIN o EDITOR)
 *   PUT    /api/cms/{colección}/{id}   {version, registro sin id}       → 200          (ADMIN o EDITOR)
 *   DELETE /api/cms/{colección}/{id}   {version}                        → 204          (solo ADMIN)
 * </pre>
 *
 * Colecciones: {@code autores} (el id se elige al crear: {@code {id, name, role, bio?, avatar?}}),
 * {@code lugares}, {@code itinerarios} y {@code proyectos} (el id es el slug inicial). Rutas
 * explícitas, una por colección, para que cada endpoint esté en la matriz de autorización.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/cms")
class CatalogEntityController {

  /** La versión es xmin como texto: solo dígitos; se compara tal cual. */
  private static final Pattern VERSION = Pattern.compile("[0-9]{1,20}");

  private final CatalogService service;

  CatalogEntityController(CatalogService service) {
    this.service = service;
  }

  // --- Autores -----------------------------------------------------------------------------------

  @GetMapping("/autores/{id}")
  @CmsEditor
  Detail author(@PathVariable("id") String id, HttpServletRequest request) {
    return find(Kind.AUTHORS, id, request);
  }

  @PostMapping("/autores")
  @CmsEditor
  ResponseEntity<Detail> createAuthor(HttpServletRequest request, Authentication authentication) {
    return create(Kind.AUTHORS, "/api/cms/autores/", request, authentication);
  }

  @PutMapping("/autores/{id}")
  @CmsEditor
  Detail updateAuthor(@PathVariable("id") String id, HttpServletRequest request, Authentication authentication) {
    return update(Kind.AUTHORS, id, request, authentication);
  }

  @DeleteMapping("/autores/{id}")
  @CmsAdmin
  ResponseEntity<Void> deleteAuthor(@PathVariable("id") String id, HttpServletRequest request,
      Authentication authentication) {
    return delete(Kind.AUTHORS, id, request, authentication);
  }

  // --- Lugares -----------------------------------------------------------------------------------

  @GetMapping("/lugares/{id}")
  @CmsEditor
  Detail destination(@PathVariable("id") String id, HttpServletRequest request) {
    return find(Kind.DESTINATIONS, id, request);
  }

  @PostMapping("/lugares")
  @CmsEditor
  ResponseEntity<Detail> createDestination(HttpServletRequest request, Authentication authentication) {
    return create(Kind.DESTINATIONS, "/api/cms/lugares/", request, authentication);
  }

  @PutMapping("/lugares/{id}")
  @CmsEditor
  Detail updateDestination(@PathVariable("id") String id, HttpServletRequest request,
      Authentication authentication) {
    return update(Kind.DESTINATIONS, id, request, authentication);
  }

  @DeleteMapping("/lugares/{id}")
  @CmsAdmin
  ResponseEntity<Void> deleteDestination(@PathVariable("id") String id, HttpServletRequest request,
      Authentication authentication) {
    return delete(Kind.DESTINATIONS, id, request, authentication);
  }

  // --- Itinerarios -------------------------------------------------------------------------------

  @GetMapping("/itinerarios/{id}")
  @CmsEditor
  Detail trip(@PathVariable("id") String id, HttpServletRequest request) {
    return find(Kind.TRIPS, id, request);
  }

  @PostMapping("/itinerarios")
  @CmsEditor
  ResponseEntity<Detail> createTrip(HttpServletRequest request, Authentication authentication) {
    return create(Kind.TRIPS, "/api/cms/itinerarios/", request, authentication);
  }

  @PutMapping("/itinerarios/{id}")
  @CmsEditor
  Detail updateTrip(@PathVariable("id") String id, HttpServletRequest request, Authentication authentication) {
    return update(Kind.TRIPS, id, request, authentication);
  }

  @DeleteMapping("/itinerarios/{id}")
  @CmsAdmin
  ResponseEntity<Void> deleteTrip(@PathVariable("id") String id, HttpServletRequest request,
      Authentication authentication) {
    return delete(Kind.TRIPS, id, request, authentication);
  }

  // --- Proyectos ---------------------------------------------------------------------------------

  @GetMapping("/proyectos/{id}")
  @CmsEditor
  Detail project(@PathVariable("id") String id, HttpServletRequest request) {
    return find(Kind.PROJECTS, id, request);
  }

  @PostMapping("/proyectos")
  @CmsEditor
  ResponseEntity<Detail> createProject(HttpServletRequest request, Authentication authentication) {
    return create(Kind.PROJECTS, "/api/cms/proyectos/", request, authentication);
  }

  @PutMapping("/proyectos/{id}")
  @CmsEditor
  Detail updateProject(@PathVariable("id") String id, HttpServletRequest request, Authentication authentication) {
    return update(Kind.PROJECTS, id, request, authentication);
  }

  @DeleteMapping("/proyectos/{id}")
  @CmsAdmin
  ResponseEntity<Void> deleteProject(@PathVariable("id") String id, HttpServletRequest request,
      Authentication authentication) {
    return delete(Kind.PROJECTS, id, request, authentication);
  }

  // --- Comunes -----------------------------------------------------------------------------------

  private Detail find(Kind kind, String id, HttpServletRequest request) {
    ArticleController.noParameters(request);
    return service.find(kind, ArticleController.articleId(id)).orElseThrow(EditorialException::notFound);
  }

  private ResponseEntity<Detail> create(Kind kind, String location, HttpServletRequest request,
      Authentication authentication) {
    ArticleController.noParameters(request);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    String id;
    if (kind.hasSlug) {
      if (body.has("id")) {
        errors.add(new FieldError("id", "el id es el slug inicial: no se envía"));
      }
      id = EditorialJson.slug(body, "slug", errors);
    } else {
      id = EditorialJson.slug(body, "id", errors);
    }
    ObjectNode record = record(id, body, Set.of());
    validate(kind, record, errors);
    String created = service.create(kind, record, ArticleEditController.userId(authentication));
    return ResponseEntity.created(URI.create(location + created)).body(service.find(kind, created).orElseThrow());
  }

  private Detail update(Kind kind, String id, HttpServletRequest request, Authentication authentication) {
    ArticleController.noParameters(request);
    String existing = ArticleController.articleId(id);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    String version = version(body, errors);
    if (body.has("id")) {
      errors.add(new FieldError("id", "el id no cambia: no se envía"));
    }
    if (kind.hasSlug) {
      EditorialJson.slug(body, "slug", errors);
    }
    ObjectNode record = record(existing, body, Set.of("version", "id"));
    validate(kind, record, errors);
    service.update(kind, existing, version, record, ArticleEditController.userId(authentication));
    return service.find(kind, existing).orElseThrow(EditorialException::notFound);
  }

  private ResponseEntity<Void> delete(Kind kind, String id, HttpServletRequest request,
      Authentication authentication) {
    ArticleController.noParameters(request);
    String existing = ArticleController.articleId(id);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    EditorialJson.onlyFields(body, Set.of("version"), errors);
    String version = version(body, errors);
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    service.delete(kind, existing, version, ArticleEditController.userId(authentication));
    return ResponseEntity.noContent().build();
  }

  /** El registro completo del contrato: el id y los campos del cuerpo (salvo los de control). */
  private static ObjectNode record(String id, JsonNode body, Set<String> control) {
    ObjectNode record = JsonNodeFactory.instance.objectNode();
    record.put("id", id == null ? "x" : id);
    for (String key : body.propertyNames()) {
      if (!control.contains(key) && !key.equals("id")) {
        record.set(key, body.get(key));
      }
    }
    return record;
  }

  /**
   * Las reglas del contrato y de integridad; todas las incidencias juntas (sin repetir una ruta que ya
   * señaló la comprobación del slug o del id).
   */
  private void validate(Kind kind, ObjectNode record, List<FieldError> errors) {
    Set<String> flagged = new HashSet<>();
    errors.forEach(e -> flagged.add(e.path()));
    for (FieldError error : service.validate(kind, record)) {
      if (!flagged.contains(error.path())) {
        errors.add(error);
      }
    }
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
  }

  private static String version(JsonNode body, List<FieldError> errors) {
    JsonNode value = body.get("version");
    if (value == null || !value.isString() || !VERSION.matcher(value.stringValue()).matches()) {
      errors.add(new FieldError("version", value == null ? "campo obligatorio"
          : "se esperaba la versión que devolvió la lectura (texto)"));
      return null;
    }
    return value.stringValue();
  }
}
