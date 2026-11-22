package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Orden único dentro de cada contenedor, con restricciones diferibles: un duplicado en el mismo
 * contenedor se rechaza al confirmar, el mismo número en otro contenedor no choca y un
 * intercambio dentro de una transacción funciona. Sin btree_gist.
 */
class OrderingTests extends PostgresSchemaTest {

  @BeforeEach
  void seed() throws Exception {
    seedCatalog();
    tx("INSERT INTO gallery (id, slug, title, layout, demo, catalog_position) VALUES ('g-dos', 'g-dos', 'Otra', 'grid', true, 2)",
        "INSERT INTO gallery_item (gallery_id, position, media_id) VALUES ('g-uno', 2, 'm-dos'), ('g-dos', 1, 'm-uno')");
  }

  @Test
  void fotosDeUnaGaleria() throws Exception {
    txFails(UNIQUE, "UPDATE gallery_item SET position = 1 WHERE gallery_id = 'g-uno' AND position = 2");
    // g-dos también tiene la posición 1: contenedores distintos, sin conflicto (ya insertado).
    tx("UPDATE gallery_item SET position = 3 WHERE gallery_id = 'g-uno' AND position = 1",
        "UPDATE gallery_item SET position = 1 WHERE gallery_id = 'g-uno' AND position = 2",
        "UPDATE gallery_item SET position = 2 WHERE gallery_id = 'g-uno' AND position = 3");
    assertThat(column("SELECT media_id FROM gallery_item WHERE gallery_id = 'g-uno' ORDER BY position"))
        .containsExactly("m-dos", "m-uno");
    // Intercambio directo, sin posición temporal: solo funciona porque la restricción es diferible.
    tx("UPDATE gallery_item SET position = CASE position WHEN 1 THEN 2 ELSE 1 END WHERE gallery_id = 'g-uno'");
    assertThat(column("SELECT media_id FROM gallery_item WHERE gallery_id = 'g-uno' ORDER BY position"))
        .containsExactly("m-uno", "m-dos");
  }

  @Test
  void paradasDeUnItinerario() throws Exception {
    tx("INSERT INTO trip (id, slug, title, summary, demo, catalog_position) VALUES ('t-dos', 't-dos', 'Otra', 'X', true, 2)",
        "INSERT INTO trip_stop (trip_id, stop_order, destination_id) VALUES ('t-dos', 1, 'd-tres'), ('t-dos', 2, 'd-uno')");
    txFails(UNIQUE, "INSERT INTO trip_stop (trip_id, stop_order, destination_id) VALUES ('t-uno', 2, 'd-tres')");
    tx("UPDATE trip_stop SET stop_order = 3 - stop_order WHERE trip_id = 't-uno'");
    assertThat(column("SELECT destination_id FROM trip_stop WHERE trip_id = 't-uno' ORDER BY stop_order"))
        .containsExactly("d-dos", "d-uno");
    assertThat(column("SELECT destination_id FROM trip_stop WHERE trip_id = 't-dos' ORDER BY stop_order"))
        .containsExactly("d-tres", "d-uno");
  }

  @Test
  void tecnologiasDeUnProyecto() throws Exception {
    tx("INSERT INTO project_stack_item (project_id, position, tag_id) VALUES ('p-uno', 2, 'costa')");
    txFails(UNIQUE, "INSERT INTO project_stack_item (project_id, position, tag_id) VALUES ('p-uno', 1, 'mar')");
    tx("UPDATE project_stack_item SET position = 3 - position WHERE project_id = 'p-uno'");
    assertThat(column("SELECT tag_id FROM project_stack_item WHERE project_id = 'p-uno' ORDER BY position"))
        .containsExactly("costa", "angular");
  }

  @Test
  void ordenEditorialDentroDeCadaSeccion() throws Exception {
    createArticle("v-uno", "viajes", 1, 1);
    createArticle("v-dos", "viajes", 2, 2);
    createArticle("m-uno", "motor", 1, 3); // el mismo orden en otra sección: sin conflicto
    createArticle("v-borrador-a", "viajes", null, 4); // borradores sin orden: los NULL no chocan
    createArticle("v-borrador-b", "viajes", null, 5);
    txFails(UNIQUE, "UPDATE article SET editorial_order = 1 WHERE id = 'v-dos'");
    tx("UPDATE article SET editorial_order = 3 - editorial_order WHERE category_id = 'viajes'"
        + " AND editorial_order IS NOT NULL");
    assertThat(column("SELECT id FROM article WHERE category_id = 'viajes' AND editorial_order IS NOT NULL"
            + " ORDER BY editorial_order"))
        .containsExactly("v-dos", "v-uno");
    // Mover un artículo a otra sección con un orden ocupado allí falla; libre, funciona.
    txFails(UNIQUE, "UPDATE article SET category_id = 'motor' WHERE id = 'v-dos'"); // v-dos es el 1; motor ya lo tiene
    tx("UPDATE article SET category_id = 'motor' WHERE id = 'v-uno'"); // v-uno es el 2: libre en motor
    assertThat(column("SELECT id FROM article WHERE category_id = 'motor' ORDER BY editorial_order"))
        .containsExactly("m-uno", "v-uno");
  }

  @Test
  void ordenDelCatalogoDeCadaColeccion() throws Exception {
    txFails(UNIQUE, "UPDATE tag SET catalog_position = 1 WHERE id = 'mar'");
    tx("UPDATE tag SET catalog_position = 3 - catalog_position WHERE id IN ('costa', 'mar')");
    assertThat(column("SELECT id FROM tag ORDER BY catalog_position")).containsExactly("mar", "costa", "angular");
    // Cada colección tiene su propia numeración: la posición 1 existe en todas.
    assertThat(one(Long.class, "SELECT count(*) FROM (SELECT catalog_position FROM tag WHERE catalog_position = 2"
        + " UNION ALL SELECT catalog_position FROM media WHERE catalog_position = 2"
        + " UNION ALL SELECT catalog_position FROM destination WHERE catalog_position = 2) AS t")).isEqualTo(3L);
  }

  @Test
  void laRestriccionDeOrdenEditorialEsUnaUniqueDiferibleSinExtensiones() throws Exception {
    assertThat(one(String.class, "SELECT contype::text || ':' || condeferrable || ':' || condeferred FROM pg_constraint"
            + " WHERE conname = 'article_order_key'"))
        .isEqualTo("u:true:true");
    assertThat(column("SELECT extname FROM pg_extension")).containsExactly("plpgsql");
  }
}
