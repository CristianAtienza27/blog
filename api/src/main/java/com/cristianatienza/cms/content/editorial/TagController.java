package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.EditorialException.FieldError;
import com.cristianatienza.cms.content.editorial.EditorialViews.TagItem;
import com.cristianatienza.cms.content.validation.CatalogRules;
import com.cristianatienza.cms.content.validation.Issue;
import com.cristianatienza.cms.security.policy.CmsAdmin;
import com.cristianatienza.cms.security.policy.CmsEditor;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gestión de etiquetas (fase 6.4.6), con CSRF. La lista sigue en {@code GET /api/cms/etiquetas}
 * (CatalogController), ahora con {@code version} e {@code inUse}.
 *
 * <pre>
 *   POST   /api/cms/etiquetas        {slug, label}            → 201 + etiqueta   (ADMIN o EDITOR)
 *   PUT    /api/cms/etiquetas/{id}   {version, slug, label}   → 200 + etiqueta   (ADMIN o EDITOR)
 *   DELETE /api/cms/etiquetas/{id}   {version}                → 204              (solo ADMIN)
 * </pre>
 *
 * La forma es exactamente la de {@code tag} en validate.ts ({@link CatalogRules}): el texto no tiene
 * más límite que el del contrato (no vacío) y el del cuerpo (1 MiB).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/cms/etiquetas")
class TagController {

  /** La versión es xmin como texto: solo dígitos; se compara tal cual. */
  private static final Pattern VERSION = Pattern.compile("[0-9]{1,20}");

  private final TagService service;

  TagController(TagService service) {
    this.service = service;
  }

  @PostMapping
  @CmsEditor
  ResponseEntity<TagItem> create(HttpServletRequest request, Authentication authentication) {
    ArticleController.noParameters(request);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    EditorialJson.onlyFields(body, Set.of("slug", "label"), errors);
    TagService.TagInput input = input(body, errors);
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    String id = service.create(input, ArticleEditController.userId(authentication));
    return ResponseEntity.created(URI.create("/api/cms/etiquetas/" + id)).body(find(id));
  }

  @PutMapping("/{id}")
  @CmsEditor
  TagItem update(@PathVariable("id") String id, HttpServletRequest request, Authentication authentication) {
    ArticleController.noParameters(request);
    String tag = ArticleController.articleId(id);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    EditorialJson.onlyFields(body, Set.of("version", "slug", "label"), errors);
    String version = version(body, errors);
    TagService.TagInput input = input(body, errors);
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    service.update(tag, version, input, ArticleEditController.userId(authentication));
    return find(tag);
  }

  @DeleteMapping("/{id}")
  @CmsAdmin
  ResponseEntity<Void> delete(@PathVariable("id") String id, HttpServletRequest request,
      Authentication authentication) {
    ArticleController.noParameters(request);
    String tag = ArticleController.articleId(id);
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    EditorialJson.onlyFields(body, Set.of("version"), errors);
    String version = version(body, errors);
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    service.delete(tag, version, ArticleEditController.userId(authentication));
    return ResponseEntity.noContent().build();
  }

  private TagItem find(String id) {
    return service.find(id).orElseThrow(EditorialException::notFound);
  }

  /** slug y label con las reglas de validate.ts (la etiqueta completa: id = slug). */
  private static TagService.TagInput input(JsonNode body, List<FieldError> errors) {
    String slug = EditorialJson.slug(body, "slug", errors);
    JsonNode label = body.get("label");
    ObjectNode tag = JsonNodeFactory.instance.objectNode();
    tag.put("id", slug == null ? "x" : slug);
    tag.put("slug", slug == null ? "x" : slug);
    if (label != null) {
      tag.set("label", label);
    }
    for (Issue issue : CatalogRules.tag(tag, "")) {
      // Solo el texto: el slug ya lo validó EditorialJson (mismas reglas, con su límite).
      if (issue.path().equals(".label")) {
        errors.add(new FieldError("label", issue.message()));
      }
    }
    return slug == null || label == null || !label.isString() ? null : new TagService.TagInput(slug,
        label.stringValue());
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
