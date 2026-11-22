package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Imágenes retiradas: ningún uso nuevo en ninguna referencia (catálogo y revisiones); las
 * referencias que ya existían siguen siendo válidas.
 */
class RetiredMediaTests extends PostgresSchemaTest {

  @BeforeEach
  void seed() throws Exception {
    seedCatalog();
    tx("UPDATE media SET retired = true WHERE id = 'm-libre'");
  }

  @Test
  void ningunUsoNuevoEnElCatalogo() {
    txFails(CHECK, "UPDATE author SET avatar_media_id = 'm-libre'");
    txFails(CHECK, "UPDATE destination SET cover_media_id = 'm-libre' WHERE id = 'd-dos'");
    txFails(CHECK, "INSERT INTO destination (id, slug, name, country, lat, lng, summary, cover_media_id, demo,"
        + " catalog_position) VALUES ('d-x', 'd-x', 'X', 'ES', 1, 1, 'X', 'm-libre', true, 9)");
    txFails(CHECK, "UPDATE trip SET cover_media_id = 'm-libre'");
    txFails(CHECK, "UPDATE project SET cover_media_id = 'm-libre'");
    txFails(CHECK, "INSERT INTO gallery_item (gallery_id, position, media_id) VALUES ('g-uno', 2, 'm-libre')");
    txFails(CHECK, "UPDATE gallery_item SET media_id = 'm-libre' WHERE gallery_id = 'g-uno'");
    txFails(CHECK, "INSERT INTO media_reserved_use (use_key, media_id, description) VALUES ('navigation.x', 'm-libre', 'X')");
  }

  @Test
  void ningunUsoNuevoEnLasRevisiones() throws Exception {
    createArticle("a-uno", "viajes", 1, 1);
    String base = content("Con retirada");
    txFails(CHECK, createRevisionSql("a-uno", null, base.replace("\"readingMinutes\"", "\"cover\": \"m-libre\", \"readingMinutes\"")));
    txFails(CHECK, createRevisionSql("a-uno", null,
        content("Imagen", "[{\"type\": \"image\", \"media\": \"m-libre\", \"layout\": \"wide\"}]")));
    txFails(CHECK, createRevisionSql("a-uno", null, content("Composición",
        "[{\"type\": \"figures\", \"layout\": \"diptych\", \"items\": [{\"media\": \"m-uno\"}, {\"media\": \"m-libre\"}]}]")));
    assertThat(one(Long.class, "SELECT count(*) FROM article_revision")).isZero();
  }

  @Test
  void lasReferenciasHistoricasSiguenSiendoValidas() throws Exception {
    // m-uno se usa en una galería, como portada de un lugar y en una revisión; después se retira.
    createArticle("a-uno", "viajes", 1, 1);
    long old = createRevision("a-uno", null,
        content("Antes", "[{\"type\": \"image\", \"media\": \"m-uno\", \"layout\": \"wide\"}]"));
    long current = createRevision("a-uno", old, content("Después, sin la imagen"));
    tx("INSERT INTO gallery_item (gallery_id, position, media_id) VALUES ('g-uno', 2, 'm-dos')");
    tx("UPDATE media SET retired = true WHERE id = 'm-uno'");

    // Las filas existentes se pueden modificar en otras columnas, reordenar y desvincular.
    tx("UPDATE gallery_item SET caption = 'Pie nuevo' WHERE media_id = 'm-uno'");
    tx("UPDATE gallery_item SET position = 3 - position WHERE gallery_id = 'g-uno'");
    tx("UPDATE destination SET name = 'Uno renombrado' WHERE id = 'd-uno'");
    tx("UPDATE destination SET cover_media_id = NULL WHERE id = 'd-uno'");
    assertThat(one(String.class, "SELECT article_revision_content(?) -> 'blocks' -> 0 ->> 'media'", old))
        .isEqualTo("m-uno");

    // Restaurar la revisión antigua sería un uso nuevo: se rechaza.
    txFails(CHECK, "SELECT * FROM restore_article_revision(" + old + ", " + current + ")");

    // Volver a activar la imagen permite usarla de nuevo.
    tx("UPDATE media SET retired = false WHERE id = 'm-uno'");
    tx("SELECT * FROM restore_article_revision(" + old + ", " + current + ")");
    assertThat(one(String.class, "SELECT origin FROM article_revision WHERE id ="
        + " (SELECT head_revision_id FROM article WHERE id = 'a-uno')")).isEqualTo("restore");
  }
}
