import { Component, DOCUMENT, PLATFORM_ID, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { RichHtml, rendersSameHtml } from './rich-html';

@Component({
  imports: [RichHtml],
  template: `<div class="prose" [appRichHtml]="html()"></div>`,
})
class Host {
  readonly html = signal('<p class="a">Uno</p><p>Dos &amp; tres</p>');
}

describe('Texto enriquecido sin reescribirse al hidratar (RichHtml)', () => {
  it('compara lo que muestra el navegador, no cómo está escrito', () => {
    const document = TestBed.inject(DOCUMENT);
    const el = document.createElement('div');
    el.innerHTML = '<p class="a">Uno</p><p>Dos &amp; tres</p>';
    expect(rendersSameHtml(el, "<p class='a'>Uno</p><p>Dos &#38; tres</p>", document)).toBe(true);
    expect(rendersSameHtml(el, '<p class="a">Uno</p><p>Dos y tres</p>', document)).toBe(false);
    expect(rendersSameHtml(document.createElement('div'), '', document)).toBe(false);
  });

  it('si el HTML es el mismo, conserva los nodos (no vuelve a pintarlos)', async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    const prose = fixture.nativeElement.querySelector('.prose') as HTMLElement;
    const first = prose.firstElementChild;
    expect(prose.textContent).toBe('UnoDos & tres');

    // Otro texto con el mismo resultado (como el HTML del servidor al hidratar).
    fixture.componentInstance.html.set("<p class='a'>Uno</p><p>Dos &#38; tres</p>");
    await fixture.whenStable();
    expect(prose.firstElementChild).toBe(first);

    fixture.componentInstance.html.set('<p>Otro texto</p>');
    await fixture.whenStable();
    expect(prose.textContent).toBe('Otro texto');
    expect(prose.firstElementChild).not.toBe(first);
  });

  it('sigue saneando: nada de scripts ni manejadores de eventos', async () => {
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.html.set('<p onclick="alert(1)">Hola</p><script>alert(2)</script>');
    await fixture.whenStable();
    const prose = fixture.nativeElement.querySelector('.prose') as HTMLElement;
    expect(prose.querySelector('script')).toBeNull();
    expect(prose.querySelector('p')?.hasAttribute('onclick')).toBe(false);
    expect(prose.textContent).toBe('Hola');
  });

  it('HTML peligroso ya en el DOM e idéntico al que llega: se sustituye por el saneado', async () => {
    const dangerous = '<p onclick="alert(1)">Hola</p><img src="x" onerror="alert(2)">';
    const fixture = TestBed.createComponent(Host);
    const prose = fixture.nativeElement.querySelector('.prose') as HTMLElement;
    // Como si el HTML del servidor llegara sin sanear y coincidiera con el de entrada.
    prose.innerHTML = dangerous;
    expect(prose.innerHTML).toBe(dangerous);
    fixture.componentInstance.html.set(dangerous);
    await fixture.whenStable();

    expect(prose.innerHTML).not.toBe(dangerous);
    expect(prose.querySelector('[onclick], [onerror]')).toBeNull();
    expect(prose.querySelector('p')?.hasAttribute('onclick')).toBe(false);
    expect(prose.textContent).toBe('Hola');
  });

  describe('en el servidor (SSR)', () => {
    beforeEach(() => {
      TestBed.configureTestingModule({ providers: [{ provide: PLATFORM_ID, useValue: 'server' }] });
    });

    it('escribe siempre el HTML saneado, aunque el DOM ya muestre el mismo', async () => {
      const fixture = TestBed.createComponent(Host);
      const prose = fixture.nativeElement.querySelector('.prose') as HTMLElement;
      prose.innerHTML = '<p class="a">Uno</p><p>Dos &amp; tres</p>';
      const before = prose.firstElementChild;
      await fixture.whenStable();
      expect(prose.textContent).toBe('UnoDos & tres');
      expect(prose.firstElementChild).not.toBe(before);
    });

    it('sanea igual que en el navegador', async () => {
      const fixture = TestBed.createComponent(Host);
      fixture.componentInstance.html.set('<p onclick="alert(1)">Hola</p><script>alert(2)</script>');
      await fixture.whenStable();
      const prose = fixture.nativeElement.querySelector('.prose') as HTMLElement;
      expect(prose.querySelector('script')).toBeNull();
      expect(prose.querySelector('p')?.hasAttribute('onclick')).toBe(false);
      expect(prose.textContent).toBe('Hola');
    });
  });
});
