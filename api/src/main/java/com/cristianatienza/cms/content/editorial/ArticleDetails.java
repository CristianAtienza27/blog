package com.cristianatienza.cms.content.editorial;

import com.cristianatienza.cms.content.editorial.EditorialViews.ArticleDetail;
import com.cristianatienza.cms.content.editorial.EditorialViews.ArticleSummary;
import com.cristianatienza.cms.content.editorial.EditorialViews.Validation;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * El detalle de un artículo con su validación (fase 6.4.3): el de la lectura de la 6.4.2 más
 * {@code validation} ({@code errors} y {@code warnings} contra el catálogo actual). Lo usan la
 * lectura y las respuestas de las escrituras, para que el CMS reciba siempre el mismo contrato.
 */
@Component
class ArticleDetails {

  private final EditorialReadRepository repository;
  private final ArticleValidation validation;

  ArticleDetails(EditorialReadRepository repository, ArticleValidation validation) {
    this.repository = repository;
    this.validation = validation;
  }

  Optional<ArticleDetail> detail(String id) {
    return repository.article(id).map(detail -> {
      ArticleValidation.Result checked = validation.checkStored(meta(detail.article()), detail.content());
      return new ArticleDetail(detail.article(), detail.content(),
          new Validation(checked.errors(), checked.warnings()));
    });
  }

  static ArticleValidation.Meta meta(ArticleSummary article) {
    return new ArticleValidation.Meta(article.id(), article.slug(), article.category(), article.order(),
        article.publishedAt(), article.updatedAt(), article.featured(), article.demo());
  }
}
