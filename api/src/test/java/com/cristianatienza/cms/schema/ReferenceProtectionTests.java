package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Referencias: una fila por ocurrencia dentro de los bloques y nada en uso se puede borrar,
 * tampoco lo que solo usa una revisión antigua.
 */
class ReferenceProtectionTests extends PostgresSchemaTest {

  private static final String BLOCKS = "["
      + "{\"type\": \"text\", \"html\": \"<p>Intro</p>\"},"
      + "{\"type\": \"image\", \"media\": \"m-uno\", \"layout\": \"full\"},"
      + "{\"type\": \"figures\", \"layout\": \"triptych\", \"items\": [{\"media\": \"m-dos\"}, {\"media\": \"m-libre\"},"
      + " {\"media\": \"m-dos\"}]},"
      + "{\"type\": \"gallery\", \"gallery\": \"g-uno\"},"
      + "{\"type\": \"map\", \"trip\": \"t-uno\", \"route\": true},"
      + "{\"type\": \"map\", \"destinations\": [\"d-tres\", \"d-uno\"], \"route\": false},"
      + "{\"type\": \"chapter\", \"id\": \"tres\", \"title\": \"Tres\", \"destination\": \"d-tres\"}]";

  private long revision;

  @BeforeEach
  void seed() throws Exception {
    seedCatalog();
    createArticle("a-uno", "viajes", 1, 1);
    revision = createRevision("a-uno", null, content("Con referencias", BLOCKS)
        .replace("\"readingMinutes\"", "\"cover\": \"m-cuatro\", \"trip\": \"t-uno\", \"project\": \"p-uno\","
            + " \"destinations\": [\"d-tres\"], \"readingMinutes\""));
  }

  @Test
  void unaFilaPorCadaOcurrenciaConSuRuta() throws Exception {
    assertThat(column("SELECT ref_path || ' ' || kind || ' ' || COALESCE(media_id, gallery_id, trip_id, destination_id)"
            + " || ' ' || block_index || ' ' || COALESCE(item_index::text, '-')"
            + " FROM article_revision_block_ref WHERE revision_id = ? ORDER BY block_index, item_index NULLS FIRST, ref_path",
            revision))
        .containsExactly(
            "blocks[1].media media m-uno 1 -",
            "blocks[2].items[0].media media m-dos 2 0",
            "blocks[2].items[1].media media m-libre 2 1",
            "blocks[2].items[2].media media m-dos 2 2", // la misma foto dos veces: dos ocurrencias
            "blocks[3].gallery gallery g-uno 3 -",
            "blocks[4].trip trip t-uno 4 -",
            "blocks[5].destinations[0] destination d-tres 5 0",
            "blocks[5].destinations[1] destination d-uno 5 1",
            "blocks[6].destination destination d-tres 6 -");
  }

  @Test
  void imagenesEnUsoNoSeBorran() throws Exception {
    // En los bloques (también dentro de una composición), como portada de la revisión, en una
    // galería y como portada de lugares, itinerarios y proyectos.
    for (String media : new String[] {"m-uno", "m-dos", "m-libre", "m-cuatro", "m-tres"}) {
      txFails(RESTRICT, "DELETE FROM media WHERE id = " + q(media));
    }
    // Avatar del autor y uso reservado (menú).
    tx(media("m-avatar", 10));
    tx("UPDATE author SET avatar_media_id = 'm-avatar'");
    txFails(RESTRICT, "DELETE FROM media WHERE id = 'm-avatar'");
    tx("UPDATE author SET avatar_media_id = NULL");
    tx("INSERT INTO media_reserved_use (use_key, media_id, description)"
        + " VALUES ('navigation.preview.viajes', 'm-avatar', 'Menú')");
    txFails(RESTRICT, "DELETE FROM media WHERE id = 'm-avatar'");
    tx("DELETE FROM media_reserved_use");
    // Sin usos: se borra, y su original con ella.
    tx("DELETE FROM media WHERE id = 'm-avatar'");
    assertThat(one(Long.class, "SELECT count(*) FROM media_original WHERE media_id = 'm-avatar'")).isZero();
  }

  @Test
  void loQueUsaUnaRevisionAntiguaTampocoSeBorra() throws Exception {
    // La revisión 2 ya no usa m-libre, pero la 1 sí (y es inmutable): sigue protegida.
    createRevision("a-uno", revision, content("Sin m-libre"));
    txFails(RESTRICT, "DELETE FROM media WHERE id = 'm-libre'");
  }

  @Test
  void elRestoDeEntidadesEnUsoNoSeBorran() {
    txFails(RESTRICT, "DELETE FROM gallery WHERE id = 'g-uno'"); // bloque gallery
    txFails(RESTRICT, "DELETE FROM trip WHERE id = 't-uno'"); // revisión y bloque map
    txFails(RESTRICT, "DELETE FROM destination WHERE id = 'd-tres'"); // destinos, map y chapter
    txFails(RESTRICT, "DELETE FROM destination WHERE id = 'd-dos'"); // parada de un itinerario
    txFails(RESTRICT, "DELETE FROM tag WHERE id = 'costa'"); // etiqueta de la revisión
    txFails(RESTRICT, "DELETE FROM tag WHERE id = 'angular'"); // pila de un proyecto
    txFails(RESTRICT, "DELETE FROM project WHERE id = 'p-uno'"); // revisión
    txFails(RESTRICT, "DELETE FROM author WHERE id = 'cristian-atienza'"); // revisión
    txFails(RESTRICT, "DELETE FROM category WHERE id = 'viajes'"); // artículo
  }

  @Test
  void loQueNadieUsaSeBorra() throws Exception {
    tx("DELETE FROM tag WHERE id = 'mar'");
    tx("DELETE FROM media WHERE id = 'm-cinco'"); // no existe: sin error
    tx("INSERT INTO gallery (id, slug, title, layout, demo, catalog_position) VALUES ('g-sola', 'g-sola', 'X', 'grid', true, 9)",
        "INSERT INTO gallery_item (gallery_id, position, media_id) VALUES ('g-sola', 1, 'm-uno')");
    tx("DELETE FROM gallery WHERE id = 'g-sola'"); // sus elementos se van con ella
    assertThat(one(Long.class, "SELECT count(*) FROM gallery_item WHERE gallery_id = 'g-sola'")).isZero();
  }
}
