import { makeStateKey } from '@angular/core';

/** Versión de la instantánea con la que el servidor renderizó la página (TransferState). */
export const SNAPSHOT_VERSION_KEY = makeStateKey<string>('content:snapshot-version');

/** v1 … v999999999: el mismo formato que el generador de la API. */
export const SNAPSHOT_VERSION = /^v[1-9][0-9]{0,8}$/;

/** Versión servida ahora mismo, para las páginas que no la traen (las prerenderizadas). */
export const SNAPSHOT_VERSION_URL = '/content/version.json';
