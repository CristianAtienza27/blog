package com.cristianatienza.cms.content.validation;

/**
 * Una incidencia de validación: la ruta del dato y el mensaje, como en los validadores TypeScript
 * ({@code toString()} da exactamente la misma línea: «articles[2].tags[0]: etiqueta inexistente «x»»).
 */
public record Issue(String path, String message) {

  @Override
  public String toString() {
    return path + ": " + message;
  }
}
