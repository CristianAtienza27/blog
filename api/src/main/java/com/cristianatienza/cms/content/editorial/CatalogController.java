package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.EditorialViews.AuthorItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.CategoryItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.DestinationItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.GalleryItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.MediaItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.ProjectItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.TagItem;
import com.cristianatienza.cms.content.editorial.EditorialViews.TripItem;
import com.cristianatienza.cms.security.policy.CmsEditor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogos de solo lectura para los selectores del CMS (fase 6.4.2): ADMIN o EDITOR con acceso
 * completo. Listas completas en el orden del catálogo, sin paginar (son pocos elementos; la
 * biblioteca de imágenes, con su paginación, es de la 6.5). Las imágenes nunca incluyen los datos
 * privados del original. La gestión de etiquetas (6.4.6) y del resto (6.4.7) llega después.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/cms")
class CatalogController {

  private final EditorialReadRepository repository;

  CatalogController(EditorialReadRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/categorias")
  @CmsEditor
  List<CategoryItem> categories(HttpServletRequest request) {
    ArticleController.noParameters(request);
    return repository.categories();
  }

  @GetMapping("/etiquetas")
  @CmsEditor
  List<TagItem> tags(HttpServletRequest request) {
    ArticleController.noParameters(request);
    return repository.tags();
  }

  @GetMapping("/autores")
  @CmsEditor
  List<AuthorItem> authors(HttpServletRequest request) {
    ArticleController.noParameters(request);
    return repository.authors();
  }

  @GetMapping("/lugares")
  @CmsEditor
  List<DestinationItem> destinations(HttpServletRequest request) {
    ArticleController.noParameters(request);
    return repository.destinations();
  }

  @GetMapping("/itinerarios")
  @CmsEditor
  List<TripItem> trips(HttpServletRequest request) {
    ArticleController.noParameters(request);
    return repository.trips();
  }

  @GetMapping("/proyectos")
  @CmsEditor
  List<ProjectItem> projects(HttpServletRequest request) {
    ArticleController.noParameters(request);
    return repository.projects();
  }

  @GetMapping("/imagenes")
  @CmsEditor
  List<MediaItem> media(HttpServletRequest request) {
    ArticleController.noParameters(request);
    return repository.media();
  }

  @GetMapping("/galerias")
  @CmsEditor
  List<GalleryItem> galleries(HttpServletRequest request) {
    ArticleController.noParameters(request);
    return repository.galleries();
  }
}
