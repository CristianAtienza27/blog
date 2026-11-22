package com.cristianatienza.cms.security.tool;

/**
 * Fallo esperado de la herramienta de seguridad, con su código de salida (2: argumentos o
 * contraseña no válidos; 3: usuario inexistente o ya existente). El mensaje nunca lleva secretos
 * ni el correo.
 */
final class ToolFailure extends RuntimeException {

  static final int INVALID = 2;
  static final int USER = 3;

  final int exitCode;

  ToolFailure(int exitCode, String message) {
    super(message, null, false, false);
    this.exitCode = exitCode;
  }
}
