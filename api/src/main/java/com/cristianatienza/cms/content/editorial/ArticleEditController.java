package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.EditorialException.FieldError;
import com.cristianatienza.cms.content.editorial.EditorialViews.ArticleDetail;
import com.cristianatienza.cms.content.editorial.EditorialViews.ValidationResult;
import com.cristianatienza.cms.security.AuthSessions;
import com.cristianatienza.cms.security.policy.CmsAdmin;
import com.cristianatienza.cms.security.policy.CmsEditor;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Escrituras editoriales (fase 6.4.3), todas con CSRF y {@link CmsEditor} (ADMIN o EDITOR con acceso
 * completo); los metadatos de un artículo publicado, solo ADMIN (lo comprueba el servicio).
 *
 * <pre>
 *   POST /api/cms/articulos                       {slug, category, featured, demo, content} → 201 + detalle
 *   PUT  /api/cms/articulos/{id}/metadatos        {version, slug, category, featured, demo, publishedAt, updatedAt} → 200
 *   POST /api/cms/articulos/{id}/revisiones       {baseRevisionId, content} → 201 (revisión nueva) o 200 (sin cambios)
 *   POST /api/cms/articulos/{id}/revisiones/{numero}/restaurar  {baseRevisionId} → 201 (revisión N+1, origin restore)
 *                                                  o 200 (el mismo contenido que la de trabajo)
 *   POST /api/cms/articulos/{id}/publicacion      {revisionId, version} → 200 (solo ADMIN, 6.4.5)
 *   POST /api/cms/articulos/{id}/despublicacion   {version} → 200 (solo ADMIN, 6.4.5)
 *   POST /api/cms/articulos/validacion            {content, article} o {content, slug, category} → 200 (no guarda nada)
 * </pre>
 *
 * Las respuestas de las escrituras son el detalle del artículo, con su validación (errores y avisos).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/cms/articulos")
class ArticleEditController {

  private final ArticleWriteService service;
  private final ArticleDetails details;
  private final ArticleValidation validation;

  ArticleEditController(ArticleWriteService service, ArticleDetails details, ArticleValidation validation) {
    this.service = service;
    this.details = details;
    this.validation = validation;
  }

  @PostMapping
  @CmsEditor
  ResponseEntity<ArticleDetail> create(HttpServletRequest request, Authentication authentication) {
    ArticleController.noParameters(request);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    EditorialJson.onlyFields(body, Set.of("slug", "category", "featured", "demo", "content"), errors);
    String slug = EditorialJson.slug(body, "slug", errors);
    String category = EditorialJson.section(body, "category", errors);
    Boolean featured = EditorialJson.bool(body, "featured", errors);
    Boolean demo = EditorialJson.bool(body, "demo", errors);
    if (!body.has("content")) {
      errors.add(new FieldError("content", "campo obligatorio"));
    }
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    String id = service.create(new ArticleWriteService.NewArticle(slug, category, featured, demo, body.get("content")),
        userId(authentication));
    return ResponseEntity.created(URI.create("/api/cms/articulos/" + id)).body(detail(id));
  }

  @PutMapping("/{id}/metadatos")
  @CmsEditor
  ArticleDetail metadata(@PathVariable("id") String id, HttpServletRequest request, Authentication authentication) {
    ArticleController.noParameters(request);
    String article = ArticleController.articleId(id);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    EditorialJson.onlyFields(body, Set.of("version", "slug", "category", "featured", "demo", "publishedAt",
        "updatedAt"), errors);
    Long version = EditorialJson.integer(body, "version", 0, errors);
    String slug = EditorialJson.slug(body, "slug", errors);
    String category = EditorialJson.section(body, "category", errors);
    Boolean featured = EditorialJson.bool(body, "featured", errors);
    Boolean demo = EditorialJson.bool(body, "demo", errors);
    String publishedAt = EditorialJson.date(body, "publishedAt", errors);
    String updatedAt = EditorialJson.date(body, "updatedAt", errors);
    if (version != null && version > Integer.MAX_VALUE) {
      errors.add(new FieldError("version", "fuera de rango"));
    }
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    service.updateMetadata(article, new ArticleWriteService.Metadata(version.intValue(), slug, category, featured, demo,
        publishedAt, updatedAt), userId(authentication), isAdmin(authentication));
    return detail(article);
  }

  @PostMapping("/{id}/revisiones")
  @CmsEditor
  ResponseEntity<ArticleDetail> save(@PathVariable("id") String id, HttpServletRequest request,
      Authentication authentication) {
    ArticleController.noParameters(request);
    String article = ArticleController.articleId(id);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    EditorialJson.onlyFields(body, Set.of("baseRevisionId", "content"), errors);
    Long base = EditorialJson.integer(body, "baseRevisionId", 1, errors);
    if (!body.has("content")) {
      errors.add(new FieldError("content", "campo obligatorio"));
    }
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    ArticleWriteService.Saved saved = service.saveRevision(article, base, body.get("content"), userId(authentication));
    return ResponseEntity.status(saved.created() ? 201 : 200).body(detail(article));
  }

  @PostMapping("/{id}/revisiones/{numero}/restaurar")
  @CmsEditor
  ResponseEntity<ArticleDetail> restore(@PathVariable("id") String id, @PathVariable("numero") String number,
      HttpServletRequest request, Authentication authentication) {
    ArticleController.noParameters(request);
    String article = ArticleController.articleId(id);
    int revision = ArticleController.revisionNumber(number);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    EditorialJson.onlyFields(body, Set.of("baseRevisionId"), errors);
    Long base = EditorialJson.integer(body, "baseRevisionId", 1, errors);
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    ArticleWriteService.Saved saved = service.restore(article, revision, base, userId(authentication));
    return ResponseEntity.status(saved.created() ? 201 : 200).body(detail(article));
  }

  @PostMapping("/{id}/publicacion")
  @CmsAdmin
  ArticleDetail publish(@PathVariable("id") String id, HttpServletRequest request, Authentication authentication) {
    ArticleController.noParameters(request);
    String article = ArticleController.articleId(id);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    EditorialJson.onlyFields(body, Set.of("revisionId", "version"), errors);
    Long revision = EditorialJson.integer(body, "revisionId", 1, errors);
    Long version = EditorialJson.integer(body, "version", 0, errors);
    if (version != null && version > Integer.MAX_VALUE) {
      errors.add(new FieldError("version", "fuera de rango"));
    }
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    service.publish(article, revision, version.intValue(), userId(authentication));
    return detail(article);
  }

  @PostMapping("/{id}/despublicacion")
  @CmsAdmin
  ArticleDetail unpublish(@PathVariable("id") String id, HttpServletRequest request, Authentication authentication) {
    ArticleController.noParameters(request);
    String article = ArticleController.articleId(id);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    EditorialJson.onlyFields(body, Set.of("version"), errors);
    Long version = EditorialJson.integer(body, "version", 0, errors);
    if (version != null && version > Integer.MAX_VALUE) {
      errors.add(new FieldError("version", "fuera de rango"));
    }
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    service.unpublish(article, version.intValue(), userId(authentication));
    return detail(article);
  }

  @PostMapping("/validacion")
  @CmsEditor
  ValidationResult validate(HttpServletRequest request) {
    ArticleController.noParameters(request);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    EditorialJson.onlyFields(body, Set.of("content", "article", "slug", "category"), errors);
    if (!body.has("content")) {
      errors.add(new FieldError("content", "campo obligatorio"));
    }
    ArticleValidation.Meta meta = null;
    if (body.has("article")) {
      if (body.has("slug") || body.has("category")) {
        errors.add(new FieldError("article", "o el artículo, o su slug y sección: no ambos"));
      }
      String article = EditorialJson.slug(body, "article", errors);
      if (errors.isEmpty()) {
        meta = details.detail(article).map(d -> ArticleDetails.meta(d.article()))
            .orElseThrow(EditorialException::notFound);
      }
    } else {
      // Un artículo nuevo: los metadatos que afectan al contenido (su propio enlace).
      String slug = EditorialJson.slug(body, "slug", errors);
      String category = EditorialJson.section(body, "category", errors);
      meta = new ArticleValidation.Meta(slug, slug, category, null, null, null, false, false);
    }
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    ArticleValidation.Result checked = validation.checkSubmitted(meta, body.get("content"));
    return new ValidationResult(checked.errors(), checked.warnings(), checked.readingMinutes());
  }

  private ArticleDetail detail(String id) {
    return details.detail(id).orElseThrow(EditorialException::notFound);
  }

  static UUID userId(Authentication authentication) {
    return AuthSessions.user(authentication).id();
  }

  private static boolean isAdmin(Authentication authentication) {
    return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
  }
}
