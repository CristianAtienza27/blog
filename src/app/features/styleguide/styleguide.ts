import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  ElementRef,
  afterNextRender,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { Horizontal } from '../../motion/directives/horizontal';
import { Parallax } from '../../motion/directives/parallax';
import { Reveal } from '../../motion/directives/reveal';
import { SplitText } from '../../motion/directives/split-text';
import { Button } from '../../shared/ui/button/button';
import { Container, ContainerSize } from '../../shared/ui/container/container';
import { Divider } from '../../shared/ui/divider/divider';
import { Eyebrow } from '../../shared/ui/eyebrow/eyebrow';
import { Link } from '../../shared/ui/link/link';
import { Media, MediaTone } from '../../shared/ui/media/media';
import { Tag } from '../../shared/ui/tag/tag';
import { ThemeToggle } from '../../shared/ui/theme-toggle/theme-toggle';

interface ColorPair {
  light: string;
  dark: string;
}

interface SectionTheme {
  id: string;
  name: string;
  accent: string;
  title: string;
  emphasis: string;
  lead: string;
  tag: string;
}

/**
 * Catálogo visual del sistema de diseño. Solo existe en desarrollo (ver app.routes.ts).
 * Lee los valores reales de las custom properties: los tokens viven únicamente en SCSS.
 */
@Component({
  selector: 'app-styleguide',
  imports: [
    Button,
    Container,
    Divider,
    Eyebrow,
    Horizontal,
    Link,
    Media,
    Parallax,
    Reveal,
    RouterLink,
    SplitText,
    Tag,
    ThemeToggle,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(window:resize)': 'measure()' },
  templateUrl: './styleguide.html',
  styleUrl: './styleguide.scss',
})
export class Styleguide {
  private readonly document = inject(DOCUMENT);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly neutrals = [
    'bg',
    'bg-raised',
    'bg-sunken',
    'ink',
    'ink-muted',
    'ink-subtle',
    'line',
    'line-strong',
  ];

  protected readonly sections: SectionTheme[] = [
    {
      id: 'viajes',
      name: 'Viajes',
      accent: 'Siena',
      title: 'La carretera que',
      emphasis: 'bordea el fin del mundo',
      lead: 'Serif ligera, itálica cálida. El tono por defecto de la revista.',
      tag: 'Galicia',
    },
    {
      id: 'fotografia',
      name: 'Fotografía',
      accent: 'Gelatina de plata',
      title: 'Luz de las',
      emphasis: 'seis de la tarde',
      lead: 'Titulares finos y eyebrows con aspecto de datos de cámara.',
      tag: 'Analógico',
    },
    {
      id: 'motor',
      name: 'Motor',
      accent: 'Rosso',
      title: 'Doce curvas antes del',
      emphasis: 'amanecer',
      lead: 'Más peso y tensión. Eyebrows condensados; el énfasis va en color.',
      tag: 'Puertos',
    },
    {
      id: 'experiencias',
      name: 'Experiencias',
      accent: 'Musgo',
      title: 'Una noche en el',
      emphasis: 'refugio del lago',
      lead: 'Cercana y cálida: la itálica toma el color del acento.',
      tag: 'Montaña',
    },
    {
      id: 'tecnologia',
      name: 'Tecnología',
      accent: 'Pizarra',
      title: 'Renderizado híbrido con',
      emphasis: 'Angular 22',
      lead: 'Misma serif, eyebrows en mono y énfasis sin itálica.',
      tag: 'Angular',
    },
  ];

  protected readonly typeScale = [
    { token: '5xl', use: 'Portada', family: 'display' },
    { token: '4xl', use: 'Display', family: 'display' },
    { token: '3xl', use: 'Título H1', family: 'display' },
    { token: '2xl', use: 'H2 · citas', family: 'display' },
    { token: 'xl', use: 'H3', family: 'display' },
    { token: 'lg', use: 'H4', family: 'display' },
    { token: 'md', use: 'Entradilla · artículo', family: 'sans' },
    { token: 'base', use: 'Texto', family: 'sans' },
    { token: 'sm', use: 'Pies de foto', family: 'sans' },
    { token: 'xs', use: 'Eyebrows · navegación', family: 'sans' },
    { token: '2xs', use: 'Metadatos', family: 'mono' },
  ];

  protected readonly spaces = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11'];
  protected readonly fluidSpaces = ['gutter', 'page-margin', 'section-s', 'section-m', 'section-l'];
  protected readonly widths = ['measure', 'narrow', 'content', 'wide', 'max'];
  protected readonly containerSizes: ContainerSize[] = ['measure', 'narrow', 'content', 'wide'];
  protected readonly breakpoints = ['sm', 'md', 'lg', 'xl'];
  protected readonly tones: MediaTone[] = ['dawn', 'sea', 'desert', 'forest', 'road', 'night'];
  protected readonly columns = Array.from({ length: 12 }, (_, i) => i + 1);

  /** Valores claro/oscuro leídos de light-dark(). */
  protected readonly colors = signal<Record<string, ColorPair>>({});
  /** Valores en bruto de custom properties (anchos, breakpoints). */
  protected readonly raw = signal<Record<string, string>>({});
  /** Tamaños calculados en px para el viewport actual. */
  protected readonly sizes = signal<Record<string, string>>({});

  constructor() {
    afterNextRender(() => this.measure());
  }

  protected measure(): void {
    const root = getComputedStyle(this.document.documentElement);
    const read = (name: string) => root.getPropertyValue(name).trim();

    const colors: Record<string, ColorPair> = {};
    for (const name of this.neutrals) colors[name] = this.parsePair(read(`--color-${name}`));
    for (const { id } of this.sections) colors[id] = this.parsePair(read(`--accent-${id}`));
    this.colors.set(colors);

    const raw: Record<string, string> = {};
    for (const name of this.widths) raw[`width-${name}`] = read(`--width-${name}`);
    for (const name of this.breakpoints) raw[`breakpoint-${name}`] = read(`--breakpoint-${name}`);
    this.raw.set(raw);

    const sizes: Record<string, string> = {};
    for (const el of this.host.nativeElement.querySelectorAll<HTMLElement>('[data-measure]')) {
      const [kind, token] = (el.dataset['measure'] ?? '').split(':');
      const style = getComputedStyle(el);
      const px = parseFloat(kind === 'font' ? style.fontSize : style.inlineSize);
      sizes[`${kind}:${token}`] = `${Math.round(px)}px`;
    }
    this.sizes.set(sizes);
  }

  private parsePair(value: string): ColorPair {
    const match = /light-dark\(\s*([^,]+),\s*([^)]+)\)/.exec(value);
    return match
      ? { light: match[1].trim(), dark: match[2].trim() }
      : { light: value, dark: value };
  }
}
