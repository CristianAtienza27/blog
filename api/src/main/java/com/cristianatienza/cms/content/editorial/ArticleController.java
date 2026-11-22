package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.EditorialViews.ArticleDetail;
import com.cristianatienza.cms.content.editorial.EditorialViews.ArticleSummary;
import com.cristianatienza.cms.content.editorial.EditorialViews.Page;
import com.cristianatienza.cms.content.editorial.EditorialViews.RevisionDetail;
import com.cristianatienza.cms.content.editorial.EditorialViews.RevisionSummary;
import com.cristianatienza.cms.security.policy.CmsEditor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lectura de artículos y de su historial (fase 6.4.2): ADMIN o EDITOR con acceso completo
 * ({@link CmsEditor}). Solo lectura: las escrituras llegan en la 6.4.3 y las siguientes.
 *
 * <pre>
 *   GET /api/cms/articulos                                listado con filtros y paginación (ArticleQuery)
 *   GET /api/cms/articulos/{id}                           metadatos, contenido de la revisión de trabajo y su
 *                                                         validación (errores y avisos, 6.4.3)
 *   GET /api/cms/articulos/{id}/revisiones                historial (page, size), del más reciente al primero
 *   GET /api/cms/articulos/{id}/revisiones/{numero}       una revisión concreta con su contenido
 * </pre>
 *
 * Un id que no puede existir (no es un slug) o un número de revisión no válido da 404, igual que uno
 * inexistente: no se distingue «mal formado» de «no existe».
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/cms/articulos")
class ArticleController {

  private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final Pattern NUMBER = Pattern.compile("[1-9][0-9]{0,8}");

  private final EditorialReadRepository repository;
  private final ArticleDetails details;

  ArticleController(EditorialReadRepository repository, ArticleDetails details) {
    this.repository = repository;
    this.details = details;
  }

  @GetMapping
  @CmsEditor
  Page<ArticleSummary> list(HttpServletRequest request) {
    return repository.articles(ArticleQuery.parse(request.getParameterMap()));
  }

  @GetMapping("/{id}")
  @CmsEditor
  ArticleDetail detail(@PathVariable("id") String id, HttpServletRequest request) {
    noParameters(request);
    return details.detail(articleId(id)).orElseThrow(EditorialException::notFound);
  }

  @GetMapping("/{id}/revisiones")
  @CmsEditor
  Page<RevisionSummary> revisions(@PathVariable("id") String id, HttpServletRequest request) {
    ArticleQuery.PageQuery page = ArticleQuery.pageOnly(request.getParameterMap());
    return repository.revisions(articleId(id), page.page(), page.size()).orElseThrow(EditorialException::notFound);
  }

  @GetMapping("/{id}/revisiones/{numero}")
  @CmsEditor
  RevisionDetail revision(@PathVariable("id") String id, @PathVariable("numero") String number,
      HttpServletRequest request) {
    noParameters(request);
    return repository.revision(articleId(id), revisionNumber(number)).orElseThrow(EditorialException::notFound);
  }

  /** Número de revisión de la ruta (1…999999999); cualquier otra cosa no puede existir: 404. */
  static int revisionNumber(String number) {
    if (!NUMBER.matcher(number).matches()) {
      throw EditorialException.notFound();
    }
    return Integer.parseInt(number);
  }

  static String articleId(String id) {
    if (!SLUG.matcher(id).matches()) {
      throw EditorialException.notFound();
    }
    return id;
  }

  static void noParameters(HttpServletRequest request) {
    if (!request.getParameterMap().isEmpty()) {
      throw EditorialException.invalidParameters(request.getParameterMap().keySet().stream()
          .map(name -> new EditorialException.FieldError(name, "parámetro desconocido"))
          .toList());
    }
  }
}
