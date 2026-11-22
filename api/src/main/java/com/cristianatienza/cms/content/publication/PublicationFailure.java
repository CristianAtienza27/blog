package com.cristianatienza.cms.content.publication;

/** Por qué no se pudo publicar o activar (el controlador lo traduce a HTTP; la herramienta, a un código). */
public final class PublicationFailure extends RuntimeException {

  public enum Kind {
    NOT_CONFIGURED(503, "publication_not_configured"),
    BUSY(409, "publication_in_progress"),
    LOCK_LOST(503, "publication_lock_lost"),
    FENCED(503, "publication_control_lost"),
    NOT_FOUND(404, "version_not_found"),
    NOT_ACTIVATABLE(409, "version_not_activatable"),
    WEB_UNAVAILABLE(503, "web_unavailable"),
    DATABASE(503, "unavailable"),
    INTERNAL(500, "internal_error");

    public final int status;
    public final String code;

    Kind(int status, String code) {
      this.status = status;
      this.code = code;
    }
  }

  private final Kind kind;

  public PublicationFailure(Kind kind, String message) {
    this(kind, message, null);
  }

  public PublicationFailure(Kind kind, String message, Throwable cause) {
    super(message, cause, false, false);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
