package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.EditorialException.FieldError;
import com.cristianatienza.cms.content.editorial.EditorialViews.ArticleSummary;
import com.cristianatienza.cms.content.editorial.EditorialViews.Page;
import com.cristianatienza.cms.security.policy.CmsAdmin;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Orden editorial de una sección (fase 6.4.5), solo ADMIN: cambia lo que muestra la web en la
 * siguiente publicación.
 *
 * <pre>
 *   PUT /api/cms/secciones/{id}/orden   {articles: [{id, version}, …]} → 200 con los artículos de la sección
 * </pre>
 *
 * La lista es la completa de los artículos con orden de la sección, en el orden nuevo; se renumera de
 * 1 a n. Una sección que no existe da 404.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/cms/secciones")
class SectionController {

  private final ArticleWriteService service;
  private final EditorialReadRepository repository;

  SectionController(ArticleWriteService service, EditorialReadRepository repository) {
    this.service = service;
    this.repository = repository;
  }

  @PutMapping("/{id}/orden")
  @CmsAdmin
  Page<ArticleSummary> reorder(@PathVariable("id") String section, HttpServletRequest request,
      Authentication authentication) {
    ArticleController.noParameters(request);
    if (!ArticleQuery.SECTIONS.contains(section)) {
      throw EditorialException.notFound();
    }
    JsonNode body = EditorialJson.object(request);
    List<FieldError> errors = new ArrayList<>();
    EditorialJson.onlyFields(body, Set.of("articles"), errors);
    JsonNode articles = body.get("articles");
    List<ArticleWriteService.Position> positions = new ArrayList<>();
    if (articles == null || !articles.isArray()) {
      errors.add(new FieldError("articles", articles == null ? "campo obligatorio" : "se esperaba una lista"));
    } else {
      for (int i = 0; i < articles.size(); i++) {
        JsonNode item = articles.get(i);
        if (!item.isObject()) {
          errors.add(new FieldError("articles[" + i + "]", "se esperaba un objeto {id, version}"));
          continue;
        }
        List<FieldError> itemErrors = new ArrayList<>();
        EditorialJson.onlyFields(item, Set.of("id", "version"), itemErrors);
        String id = EditorialJson.slug(item, "id", itemErrors);
        Long version = EditorialJson.integer(item, "version", 0, itemErrors);
        if (version != null && version > Integer.MAX_VALUE) {
          itemErrors.add(new FieldError("version", "fuera de rango"));
        }
        int index = i;
        itemErrors.forEach(e -> errors.add(new FieldError("articles[" + index + "]." + e.path(), e.message())));
        if (itemErrors.isEmpty()) {
          positions.add(new ArticleWriteService.Position(id, version.intValue()));
        }
      }
    }
    if (!errors.isEmpty()) {
      throw EditorialException.invalidContent(errors);
    }
    service.reorder(section, positions, ArticleEditController.userId(authentication));
    return repository.articles(new ArticleQuery(null, section, null, null, null, null, "seccion", 0,
        ArticleQuery.MAX_SIZE));
  }
}
