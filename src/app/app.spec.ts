import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { MediaRepository } from './content/repositories/media-repository';

describe('App (estructura común)', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([]),
        { provide: MediaRepository, useValue: { mediaList: () => Promise.resolve([]) } },
      ],
    }).compileComponents();
  });

  it('contiene enlace de salto, header, contenido, footer y menú', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('.skip-link')?.textContent).toContain('Saltar al contenido');
    expect(element.querySelector('app-header')).not.toBeNull();
    expect(element.querySelector('main#contenido router-outlet')).not.toBeNull();
    expect(element.querySelector('app-footer')?.classList.contains('scheme-dark')).toBe(true);
    expect(element.querySelector('dialog#site-menu')).not.toBeNull();
  });

  it('el enlace de salto lleva el foco al contenido principal', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    document.body.appendChild(element);

    element.querySelector<HTMLAnchorElement>('.skip-link')?.click();
    expect(document.activeElement?.id).toBe('contenido');
    element.remove();
  });
});
