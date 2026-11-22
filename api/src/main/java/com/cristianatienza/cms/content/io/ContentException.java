package com.cristianatienza.cms.content.io;

import java.util.List;

/** El contenido no se puede importar o exportar tal como está; incluye todas las incidencias. */
public class ContentException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final List<String> issues;

  public ContentException(String issue) {
    this(List.of(issue));
  }

  public ContentException(List<String> issues) {
    super(issues.size() == 1 ? issues.getFirst() : "Incidencias (" + issues.size() + "):\n- " + String.join("\n- ", issues));
    this.issues = List.copyOf(issues);
  }

  public List<String> issues() {
    return issues;
  }
}
