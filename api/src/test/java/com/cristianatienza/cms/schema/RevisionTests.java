package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Historial lineal, conflictos, restauración como revisión nueva y validación del contenido. */
class RevisionTests extends PostgresSchemaTest {

  @BeforeEach
  void seed() throws Exception {
    seedCatalog();
    createArticle("a-uno", "viajes", 1, 1);
    createArticle("a-dos", "viajes", 2, 2);
  }

  private static String row(long id) throws Exception {
    return one(String.class, "SELECT number || '|' || origin || '|' || COALESCE(parent_revision_id::text, '-') || '|'"
        + " || COALESCE(restored_from_revision_id::text, '-') FROM article_revision WHERE id = ?", id);
  }

  private static Long head(String article) throws Exception {
    return one(Long.class, "SELECT head_revision_id FROM article WHERE id = ?", article);
  }

  @Test
  void laPrimeraRevisionYLaSiguienteFormanUnHistorialLineal() throws Exception {
    long r1 = createRevision("a-uno", null, content("Primera"));
    assertThat(row(r1)).isEqualTo("1|edit|-|-");
    assertThat(head("a-uno")).isEqualTo(r1);
    long r2 = createRevision("a-uno", r1, content("Segunda"));
    assertThat(row(r2)).isEqualTo("2|edit|" + r1 + "|-");
    assertThat(head("a-uno")).isEqualTo(r2);
  }

  @Test
  void guardarSobreUnaRevisionQueNoEsLaActualEsUnConflicto() throws Exception {
    long r1 = createRevision("a-uno", null, content("Primera"));
    long r2 = createRevision("a-uno", r1, content("Segunda"));
    // Otra pestaña que aún parte de r1: no sobrescribe ni ramifica.
    txFails(REVISION_CONFLICT, createRevisionSql("a-uno", r1, content("Desde una versión vieja")));
    txFails(REVISION_CONFLICT, createRevisionSql("a-uno", null, content("Como si fuera la primera")));
    // La revisión actual de OTRO artículo tampoco sirve de padre.
    long other = createRevision("a-dos", null, content("Otro"));
    txFails(REVISION_CONFLICT, createRevisionSql("a-uno", other, content("Padre ajeno")));
    assertThat(one(Long.class, "SELECT count(*) FROM article_revision WHERE article_id = 'a-uno'")).isEqualTo(2L);
    assertThat(head("a-uno")).isEqualTo(r2);
  }

  @Test
  void restaurarCreaUnaRevisionNuevaYConservaElHistorial() throws Exception {
    long r1 = createRevision("a-uno", null, content("Primera").replace("[\"costa\"]", "[\"costa\", \"mar\"]"));
    long r2 = createRevision("a-uno", r1, content("Segunda"));
    String before1 = one(String.class, "SELECT article_revision_content(?)::text", r1);
    String before2 = one(String.class, "SELECT article_revision_content(?)::text", r2);

    long r3 = one(Long.class, "SELECT new_revision_id FROM restore_article_revision(?::bigint, ?::bigint)", r1, r2);

    assertThat(row(r3)).isEqualTo("3|restore|" + r2 + "|" + r1);
    assertThat(head("a-uno")).isEqualTo(r3);
    assertThat(one(String.class, "SELECT article_revision_content(?)::text", r3)).isEqualTo(before1);
    // El historial no cambia: r1 y r2 siguen exactamente igual.
    assertThat(one(String.class, "SELECT article_revision_content(?)::text", r1)).isEqualTo(before1);
    assertThat(one(String.class, "SELECT article_revision_content(?)::text", r2)).isEqualTo(before2);
    assertThat(column("SELECT number::text FROM article_revision WHERE article_id = 'a-uno' ORDER BY number"))
        .containsExactly("1", "2", "3");
    // La revisión restaurada es editable: se puede seguir guardando sobre ella.
    long r4 = createRevision("a-uno", r3, content("Cuarta"));
    assertThat(row(r4)).isEqualTo("4|edit|" + r3 + "|-");
  }

  @Test
  void restaurarDesdeUnaRevisionQueNoEsLaActualEsUnConflicto() throws Exception {
    long r1 = createRevision("a-uno", null, content("Primera"));
    long r2 = createRevision("a-uno", r1, content("Segunda"));
    txFails(REVISION_CONFLICT, "SELECT * FROM restore_article_revision(" + r1 + ", " + r1 + ")");
    assertThat(head("a-uno")).isEqualTo(r2);
  }

  @Test
  void elContenidoSeValidaAntesDeEscribirNada() throws Exception {
    String ok = content("Válido");
    txFails(INVALID_PARAMETER, createRevisionSql("a-uno", null, ok.replace("\"dek\"", "\"subtitulo\"")));
    txFails(INVALID_PARAMETER, createRevisionSql("a-uno", null, ok.replace("\"dek\": \"Entradilla\", ", "")));
    txFails(INVALID_PARAMETER, createRevisionSql("a-uno", null, ok.replace("[\"costa\"]", "\"costa\"")));
    txFails(INVALID_PARAMETER, createRevisionSql("a-uno", null, ok.replace("[\"costa\"]", "[1]")));
    txFails(INVALID_PARAMETER, createRevisionSql("a-uno", null, ok.replace("\"readingMinutes\": 1", "\"readingMinutes\": 1.5")));
    txFails(INVALID_PARAMETER, createRevisionSql("a-uno", null, ok.replace("\"readingMinutes\"", "\"seo\": \"x\", \"readingMinutes\"")));
    txFails(INVALID_PARAMETER, createRevisionSql("a-uno", null, ok.replace("\"title\": \"Válido\"", "\"title\": 7")));
    txFails(CHECK, createRevisionSql("a-uno", null, content("Bloque", "[{\"type\": \"video\"}]")));
    txFails(CHECK, createRevisionSql("a-uno", null, content("Vacío", "[]")));
    txFails(CHECK, createRevisionSql("a-uno", null, ok.replace("\"format\": \"guia\"", "\"format\": \"cronica\"")));
    txFails(FOREIGN_KEY, createRevisionSql("a-uno", null, ok.replace("[\"costa\"]", "[\"inexistente\"]")));
    txFails(FOREIGN_KEY, createRevisionSql("a-uno", null, ok.replace("cristian-atienza", "nadie")));
    txFails(INVALID_PARAMETER,
        "SELECT * FROM create_article_revision('a-uno', NULL, 'restore', " + q(ok) + "::jsonb)");
    assertThat(one(Long.class, "SELECT count(*) FROM article_revision")).isZero();
    assertThat(head("a-uno")).isNull();
  }

  @Test
  void destinosAusentesYVaciosSeDistinguen() throws Exception {
    long absent = createRevision("a-uno", null, content("Sin destinos"));
    long empty = createRevision("a-uno", absent,
        content("Lista vacía").replace("\"readingMinutes\"", "\"destinations\": [], \"readingMinutes\""));
    long two = createRevision("a-uno", empty,
        content("Dos").replace("\"readingMinutes\"", "\"destinations\": [\"d-tres\", \"d-uno\"], \"readingMinutes\""));
    assertThat(one(String.class, "SELECT COALESCE(article_revision_content(?) -> 'destinations', 'null')::text", absent))
        .isEqualTo("null");
    assertThat(one(String.class, "SELECT (article_revision_content(?) -> 'destinations')::text", empty)).isEqualTo("[]");
    assertThat(one(String.class, "SELECT (article_revision_content(?) -> 'destinations')::text", two))
        .isEqualTo("[\"d-tres\", \"d-uno\"]");
  }
}
