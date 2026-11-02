import { Injectable, signal } from '@angular/core';

/** Estado compartido del menú overlay (lo abren el header y lo cierra el propio menú o el router). */
@Injectable({ providedIn: 'root' })
export class MenuState {
  private readonly open = signal(false);

  readonly isOpen = this.open.asReadonly();

  show(): void {
    this.open.set(true);
  }

  hide(): void {
    this.open.set(false);
  }
}
