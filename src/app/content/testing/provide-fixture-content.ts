import { Provider } from '@angular/core';
import { CONTENT_SOURCE, ContentSource } from '../json/content-source';
import { withJsonContent } from '../json/with-json-content';
import { provideContent } from '../provide-content';

/** Adaptador JSON con un origen de datos de prueba. */
export function provideFixtureContent(
  source: ContentSource,
): (Provider | ReturnType<typeof provideContent>)[] {
  return [provideContent(withJsonContent()), { provide: CONTENT_SOURCE, useValue: source }];
}
