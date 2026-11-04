import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SECTIONS } from '../../layout/navigation';
import { Reveal } from '../../motion/directives/reveal';
import { SplitText } from '../../motion/directives/split-text';
import { Eyebrow } from '../../shared/ui/eyebrow/eyebrow';
import { Link } from '../../shared/ui/link/link';
import { Tag } from '../../shared/ui/tag/tag';
import { NotFound } from '../not-found/not-found';
import type { ArticlePreviewData } from './article.resolver';

/**
 * Página provisional de un artículo (/<sección>/<slug>): título, entradilla, sección y aviso
 * de contenido de demostración. Mantiene los enlaces de la portada rastreables hasta que
 * llegue la página de artículo definitiva. Sin artículo, 404.
 */
@Component({
  selector: 'app-article-preview',
  imports: [Eyebrow, Link, NotFound, Reveal, RouterLink, SplitText, Tag],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './article-preview.html',
  styleUrl: './article-preview.scss',
})
export class ArticlePreview {
  /** Del resolver de la ruta (withComponentInputBinding). */
  readonly article = input<ArticlePreviewData | null>(null);

  protected readonly section = computed(() =>
    SECTIONS.find((section) => section.id === this.article()?.category.id),
  );
}
