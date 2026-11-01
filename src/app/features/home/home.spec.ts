import { TestBed } from '@angular/core/testing';
import { Home } from './home';

describe('Home', () => {
  it('muestra el nombre de la revista', async () => {
    const fixture = TestBed.createComponent(Home);
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('h1')?.textContent).toContain('Cristian Atienza');
  });
});
