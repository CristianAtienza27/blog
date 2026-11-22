package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Restricciones del contrato: unicidad, forma, reglas cruzadas, coordenadas, demo y contrato completo. */
class ConstraintTests extends PostgresSchemaTest {

  @BeforeEach
  void seed() throws Exception {
    seedCatalog();
  }

  // --- Unicidad --------------------------------------------------------------------------------

  @Test
  void slugsUnicos() throws Exception {
    txFails(UNIQUE, "INSERT INTO tag (id, slug, label, catalog_position) VALUES ('otra', 'costa', 'Otra', 9)");
    txFails(UNIQUE, "INSERT INTO gallery (id, slug, title, layout, demo, catalog_position)"
        + " VALUES ('g-otra', 'g-uno', 'X', 'grid', true, 9)");
    txFails(UNIQUE, "INSERT INTO destination (id, slug, name, country, lat, lng, summary, demo, catalog_position)"
        + " VALUES ('d-otro', 'd-uno', 'X', 'ES', 1, 1, 'X', true, 9)");
    txFails(UNIQUE, "INSERT INTO trip (id, slug, title, summary, demo, catalog_position)"
        + " VALUES ('t-otro', 't-uno', 'X', 'X', true, 9)");
    txFails(UNIQUE, "INSERT INTO project (id, slug, name, summary, status, year, demo, catalog_position)"
        + " VALUES ('p-otro', 'p-uno', 'X', 'X', 'activo', 2026, true, 9)");
    createArticle("a-uno", "viajes", 1, 1);
    txFails(UNIQUE, "INSERT INTO article (id, slug, category_id, featured, demo, catalog_position)"
        + " VALUES ('a-otro', 'a-uno', 'motor', false, true, 9)");
  }

  @Test
  void originalYFuenteExternaUnicos() throws Exception {
    String[] copy = media("m-copia", 9);
    // Mismo SHA-256 que m-uno: el mismo original dos veces.
    copy[1] = copy[1].replace("md5('m-copia') || md5('m-copia-2')", "md5('m-uno') || md5('m-uno-2')");
    txFails(UNIQUE, copy);
    String[] sameSource = media("m-fuente", 9);
    sameSource[0] = sameSource[0].replace("File:m-fuente", "File:m-uno");
    txFails(UNIQUE, sameSource);
  }

  // --- Imágenes ----------------------------------------------------------------------------------

  @Test
  void unaImagenSinOriginalSeRechazaAlConfirmar() {
    txFails(NOT_NULL, media("m-sola", 9)[0]);
  }

  @Test
  void reglasDelCreditoYDelOriginal() {
    // Foto propia con URL de origen.
    String[] own = media("m-propia", 9);
    own[0] = own[0].replace("'wikimedia'", "'propia'").replace("'CC-BY-4.0'", "'derechos-reservados'");
    txFails(CHECK, own);
    // Foto propia sin URL de origen pero con el original público.
    String[] ownPublicOriginal = media("m-propia", 9);
    ownPublicOriginal[0] = ownPublicOriginal[0]
        .replace("'wikimedia'", "'propia'")
        .replace("'CC-BY-4.0'", "'derechos-reservados'")
        .replace("'https://commons.wikimedia.org/wiki/File:m-propia'", "NULL");
    txFails(CHECK, ownPublicOriginal);
    // Fuente externa con derechos reservados.
    String[] reserved = media("m-externa", 9);
    reserved[0] = reserved[0].replace("'CC-BY-4.0'", "'derechos-reservados'");
    txFails(CHECK, reserved);
    // Fuente externa sin URL del original.
    String[] noUrl = media("m-externa", 9);
    noUrl[1] = noUrl[1].replace("'https://upload.wikimedia.org/m-externa.jpg'", "NULL");
    txFails(CHECK, noUrl);
    // Versión optimizada mayor que el original.
    String[] tooWide = media("m-ancha", 9);
    tooWide[1] = tooWide[1].replace("3000, 2000", "1200, 900");
    txFails(CHECK, tooWide);
  }

  @Test
  void anchosYVistaPrevia() {
    String[] notAscending = media("m-anchos", 9);
    notAscending[0] = notAscending[0].replace("'{640,1024,1600}'", "'{1024,640}'");
    txFails(CHECK, notAscending);
    String[] tooLarge = media("m-anchos", 9);
    tooLarge[0] = tooLarge[0].replace("'{640,1024,1600}'", "'{640,2400}'");
    txFails(CHECK, tooLarge);
    String[] empty = media("m-anchos", 9);
    empty[0] = empty[0].replace("'{640,1024,1600}'", "'{}'");
    txFails(CHECK, empty);
    String[] bigPlaceholder = media("m-grande", 9);
    bigPlaceholder[0] = bigPlaceholder[0].replace("UklGRg==", "A".repeat(4000));
    txFails(CHECK, bigPlaceholder);
  }

  @Test
  void focalEsObligatorioEnLaImagen() {
    String[] noFocal = media("m-focal", 9);
    noFocal[0] = noFocal[0].replace(" focal_x, focal_y,", "").replace(" 'Pie', 0.5, 0.5,", " 'Pie',");
    txFails(NOT_NULL, noFocal);
    String[] outOfRange = media("m-focal", 9);
    outOfRange[0] = outOfRange[0].replace("'Pie', 0.5, 0.5", "'Pie', 1.5, 0.5");
    txFails(CHECK, outOfRange);
  }

  @Test
  void camaraConLasClavesYTiposDelContrato() throws Exception {
    tx("UPDATE media SET camera = '{}' WHERE id = 'm-uno'");
    tx("UPDATE media SET camera = '{\"make\": \"Apple\", \"model\": \"iPhone\", \"lens\": \"6.7mm\","
        + " \"focalLength\": 6.765, \"aperture\": 1.78, \"exposure\": \"1/4673\", \"iso\": 80}' WHERE id = 'm-uno'");
    txFails(CHECK, "UPDATE media SET camera = '{\"gps\": \"41,2\"}' WHERE id = 'm-uno'");
    txFails(CHECK, "UPDATE media SET camera = '{\"iso\": 80.5}' WHERE id = 'm-uno'");
    txFails(CHECK, "UPDATE media SET camera = '{\"exposure\": \"rápida\"}' WHERE id = 'm-uno'");
    txFails(CHECK, "UPDATE media SET camera = '{\"make\": \"\"}' WHERE id = 'm-uno'");
    txFails(CHECK, "UPDATE media SET camera = '[]' WHERE id = 'm-uno'");
  }

  // --- Coordenadas -------------------------------------------------------------------------------

  @Test
  void coordenadasDeLugares() throws Exception {
    txFails(CHECK, "UPDATE destination SET lat = 90.5 WHERE id = 'd-uno'");
    txFails(CHECK, "UPDATE destination SET lng = -180.5 WHERE id = 'd-uno'");
    tx("UPDATE destination SET lat = 41.123456, lng = -9.654321 WHERE id = 'd-uno'");
    assertThat(one(BigDecimal.class, "SELECT lat FROM destination WHERE id = 'd-uno'"))
        .isEqualByComparingTo("41.123456");
    // Más de 6 decimales: PostgreSQL redondea en silencio. Por eso CoordinatePolicy los rechaza
    // en el backend antes de guardar.
    tx("UPDATE destination SET lat = 41.1234567 WHERE id = 'd-uno'");
    assertThat(one(BigDecimal.class, "SELECT lat FROM destination WHERE id = 'd-uno'"))
        .isEqualByComparingTo("41.123457");
  }

  @Test
  void ubicacionPublicaDeUnaFotoConDosDecimalesComoMaximo() throws Exception {
    tx("UPDATE media SET location_name = 'Cadaqués', location_lat = 42.29, location_lng = 3.28 WHERE id = 'm-uno'");
    txFails(CHECK, "UPDATE media SET location_lat = 42.288 WHERE id = 'm-uno'");
    txFails(CHECK, "UPDATE media SET location_lat = 95 WHERE id = 'm-uno'");
    txFails(CHECK, "UPDATE media SET location_lat = NULL WHERE id = 'm-uno'"); // las dos o ninguna
    txFails(CHECK, "UPDATE media SET location_name = NULL WHERE id = 'm-uno'"); // punto sin lugar
    // Documentado: 42.2900001 se redondea a 42.29 y PASA el CHECK. La base de datos sola no
    // puede detectarlo; CoordinatePolicy.PHOTO_LOCATION lo rechaza antes de guardar.
    tx("UPDATE media SET location_lat = 42.2900001 WHERE id = 'm-uno'");
    assertThat(one(BigDecimal.class, "SELECT location_lat FROM media WHERE id = 'm-uno'"))
        .isEqualByComparingTo("42.29");
  }

  // --- demo ---------------------------------------------------------------------------------------

  @Test
  void demoObligatorioYSinValorPorDefectoEnLasSeisEntidades() throws Exception {
    List<String> tables = column(
        "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'demo'"
            + " AND is_nullable = 'NO' AND column_default IS NULL AND data_type = 'boolean' ORDER BY table_name");
    assertThat(tables).containsExactly("article", "destination", "gallery", "media", "project", "trip");
    assertThat(column("SELECT table_name FROM information_schema.columns WHERE table_schema = 'public'"
            + " AND column_name = 'demo' ORDER BY table_name"))
        .hasSize(6);
    txFails(NOT_NULL, "INSERT INTO trip (id, slug, title, summary, catalog_position) VALUES ('t-x', 't-x', 'X', 'X', 9)");
  }

  // --- Artículos -------------------------------------------------------------------------------

  @Test
  void fechasYPublicacionCompleta() throws Exception {
    createArticle("a-uno", "viajes", 1, 1);
    txFails(CHECK, "UPDATE article SET updated_at = '2026-09-01' WHERE id = 'a-uno'"); // antes de publicar
    tx("UPDATE article SET updated_at = '2026-10-01' WHERE id = 'a-uno'");
    long revision = createRevision("a-uno", null, content("Uno"));
    // El puntero publicado solo lo mueve el rol de una operación autorizada (V4): ni siquiera el
    // propietario con un UPDATE directo. Para comprobar las restricciones, el test asume esa
    // identidad (lo que hace la operación import_published_article).
    String publish = "UPDATE article SET published_revision_id = " + revision + " WHERE id = 'a-uno'";
    txFails(NO_PRIVILEGE, publish);
    // Publicada sin fecha u orden: el contrato de ArticleRecord los exige.
    tx("UPDATE article SET published_at = NULL, updated_at = NULL WHERE id = 'a-uno'");
    txFails(CHECK, "SET LOCAL ROLE cms_import_writer", publish);
    tx("UPDATE article SET published_at = '2026-09-18', editorial_order = NULL WHERE id = 'a-uno'");
    txFails(CHECK, "SET LOCAL ROLE cms_import_writer", publish);
    tx("UPDATE article SET editorial_order = 1 WHERE id = 'a-uno'");
    tx("SET LOCAL ROLE cms_import_writer", publish);
    assertThat(one(Long.class, "SELECT published_revision_id FROM article WHERE id = 'a-uno'")).isEqualTo(revision);
  }

  // --- Contrato completo ---------------------------------------------------------------------------

  @Test
  void elContratoCompletoCabeSinPerderNada() throws Exception {
    // Imagen propia con todos los campos opcionales del contrato (incluidos los que hoy no se usan).
    tx("INSERT INTO media (id, path, width, height, widths, alt, title, caption, description, taken_at,"
            + " location_name, location_region, location_country, location_lat, location_lng, camera, focal_x,"
            + " focal_y, placeholder, credit_author, credit_source, credit_license, demo, catalog_position) VALUES"
            + " ('m-completa', 'fotografia/m-completa.89abcdef', 2400, 1800, '{640,1024,1600,2400}', 'Alt',"
            + " 'Título', 'Pie', 'Descripción larga', '2026-05-01', 'Zahara', 'Cádiz', 'ES', 36.84, -5.39,"
            + " '{\"make\": \"Apple\", \"model\": \"iPhone 17 Pro Max\", \"lens\": \"6.765mm f/1.78\","
            + " \"focalLength\": 6.765, \"aperture\": 1.78, \"exposure\": \"1/4673\", \"iso\": 80}', 0.33, 0.67,"
            + " 'data:image/webp;base64,UklGRg==', 'Cristian Atienza', 'propia', 'derechos-reservados', false, 9)",
        "INSERT INTO media_original (media_id, file, format, width, height, bytes, sha256, raw_file, raw_bytes,"
            + " raw_sha256) VALUES ('m-completa', 'm-completa.heic', 'heic', 4284, 5712, 4270928, md5('c') || md5('d'),"
            + " 'm-completa.dng', 75000000, md5('e') || md5('f'))",
        "UPDATE author SET bio = 'Escribe y fotografía.', avatar_media_id = 'm-completa' WHERE id = 'cristian-atienza'",
        "UPDATE gallery SET description = 'Serie del Mediterráneo' WHERE id = 'g-uno'",
        "UPDATE gallery_item SET caption = 'Pie en la serie' WHERE gallery_id = 'g-uno'",
        "UPDATE destination SET elevation = 2757 WHERE id = 'd-tres'",
        "UPDATE trip_stop SET note = 'Parada larga', distance_km = 12.5, mode = 'a-pie' WHERE stop_order = 2",
        "UPDATE project SET repository_url = 'https://github.com/ejemplo/repo' WHERE id = 'p-uno'",
        "INSERT INTO media_reserved_use (use_key, media_id, description) VALUES"
            + " ('navigation.preview.viajes', 'm-uno', 'Vista previa de Viajes en el menú')");
    assertThat(one(String.class, "SELECT row(title, description, taken_at, location_name, location_region,"
            + " location_country, location_lat, location_lng, focal_x, focal_y)::text FROM media WHERE id = 'm-completa'"))
        .isEqualTo("(Título,\"Descripción larga\",2026-05-01,Zahara,Cádiz,ES,36.840000,-5.390000,0.33,0.67)");
    assertThat(one(String.class, "SELECT camera::text FROM media WHERE id = 'm-completa'"))
        .contains("\"iso\": 80", "\"focalLength\": 6.765", "\"exposure\": \"1/4673\"");
    assertThat(one(String.class, "SELECT distance_km::text FROM trip_stop WHERE stop_order = 2")).isEqualTo("12.5");

    // Revisión con todos los campos opcionales y todos los bloques con todos sus opcionales.
    createArticle("a-completo", "viajes", 1, 1);
    tx("UPDATE article SET updated_at = '2026-10-01', featured = true WHERE id = 'a-completo'");
    String blocks = "["
        + "{\"type\": \"text\", \"html\": \"<p>Texto con <a href=\\\"#capitulo\\\">ancla</a></p>\"},"
        + "{\"type\": \"chapter\", \"id\": \"capitulo\", \"number\": 1, \"title\": \"Capítulo\","
        + " \"kicker\": \"Antetítulo\", \"destination\": \"d-uno\"},"
        + "{\"type\": \"image\", \"media\": \"m-uno\", \"layout\": \"wide\", \"caption\": \"Pie\", \"ratio\": \"4/5\","
        + " \"align\": \"start\", \"focal\": {\"x\": 0.25, \"y\": 0.75}},"
        + "{\"type\": \"figures\", \"layout\": \"triptych\", \"items\": [{\"media\": \"m-uno\", \"ratio\": \"3/2\","
        + " \"focal\": {\"x\": 0.10, \"y\": 0.9}, \"caption\": \"a\"}, {\"media\": \"m-dos\"}, {\"media\": \"m-cuatro\"}]},"
        + "{\"type\": \"gallery\", \"gallery\": \"g-uno\"},"
        + "{\"type\": \"quote\", \"text\": \"Cita\", \"cite\": \"Alguien\", \"variant\": \"pull\"},"
        + "{\"type\": \"map\", \"trip\": \"t-uno\", \"route\": true, \"caption\": \"Ruta\"},"
        + "{\"type\": \"map\", \"destinations\": [\"d-uno\", \"d-tres\"], \"route\": false},"
        + "{\"type\": \"code\", \"language\": \"typescript\", \"code\": \"const a = 1;\", \"filename\": \"a.ts\","
        + " \"caption\": \"Código\"},"
        + "{\"type\": \"specs\", \"title\": \"Ficha\", \"items\": [{\"label\": \"Motor\", \"value\": \"1.2\","
        + " \"unit\": \"l\"}, {\"label\": \"Peso\", \"value\": \"200\"}], \"note\": \"Nota\"},"
        + "{\"type\": \"divider\", \"variant\": \"ornament\"}]";
    String full = "{\"title\": \"Completo\", \"dek\": \"Entradilla\", \"excerpt\": \"Extracto\","
        + " \"format\": \"reportaje\", \"author\": \"cristian-atienza\", \"tags\": [\"costa\", \"mar\", \"costa\"],"
        + " \"cover\": \"m-completa\", \"trip\": \"t-uno\", \"project\": \"p-uno\","
        + " \"seo\": {\"description\": \"Descripción SEO\"}, \"destinations\": [], \"readingMinutes\": 3,"
        + " \"blocks\": " + blocks + "}";
    long id = createRevision("a-completo", null, full);
    assertThat(one(Boolean.class, "SELECT article_revision_content(?) = ?::jsonb", id, full))
        .as("la revisión devuelve exactamente el contenido guardado").isTrue();

    // Ausente frente a vacío: seo {} y sin destinations.
    String minimal = content("Mínimo").replace("\"readingMinutes\"", "\"seo\": {}, \"readingMinutes\"");
    long second = createRevision("a-completo", id, minimal);
    assertThat(one(Boolean.class, "SELECT article_revision_content(?) = ?::jsonb", second, minimal)).isTrue();
    // jsonb_exists en lugar del operador «?», que JDBC tomaría por un parámetro.
    assertThat(one(Boolean.class, "SELECT jsonb_exists(article_revision_content(?), 'destinations')", second)).isFalse();
    assertThat(one(Boolean.class, "SELECT jsonb_exists(article_revision_content(?), 'destinations')", id)).isTrue();
  }
}
