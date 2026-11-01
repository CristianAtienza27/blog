import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Button } from './button/button';
import { Eyebrow } from './eyebrow/eyebrow';
import { Media } from './media/media';

@Component({
  imports: [Button, Eyebrow, Media],
  template: `
    <a app-button variant="outline" arrow href="/">Leer</a>
    <app-eyebrow [index]="3">Motor</app-eyebrow>
    <app-media tone="sea" label="Mar" caption="Costa" meta="f/8" />
  `,
})
class Host {}

describe('Componentes base', () => {
  let element: HTMLElement;

  beforeEach(async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    element = fixture.nativeElement as HTMLElement;
  });

  it('el botón conserva el elemento nativo y expone su variante', () => {
    const link = element.querySelector('a[app-button]');
    expect(link?.getAttribute('href')).toBe('/');
    expect(link?.getAttribute('data-variant')).toBe('outline');
    expect(link?.querySelector('svg[aria-hidden="true"]')).not.toBeNull();
  });

  it('el eyebrow numera con dos cifras', () => {
    expect(element.querySelector('app-eyebrow')?.textContent).toContain('03');
  });

  it('el medio provisional es accesible y muestra el pie', () => {
    const placeholder = element.querySelector('[role="img"]');
    expect(placeholder?.getAttribute('aria-label')).toBe('Mar');
    expect(element.querySelector('figcaption')?.textContent).toContain('Costa');
  });
});
