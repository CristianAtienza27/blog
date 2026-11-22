package com.cristianatienza.cms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Cada clave foránea tiene un índice cuyas primeras columnas son las de la clave. */
class IndexCoverageTests extends PostgresSchemaTest {

  @Test
  void ningunaClaveForaneaSinIndice() throws Exception {
    var uncovered = column("""
        SELECT c.conrelid::regclass || '.' || c.conname
        FROM pg_constraint AS c
        WHERE c.contype = 'f'
          AND c.connamespace = 'public'::regnamespace
          AND NOT EXISTS (
            SELECT 1
            FROM pg_index AS i
            WHERE i.indrelid = c.conrelid
              AND (SELECT array_agg(k ORDER BY k)
                   FROM unnest((i.indkey::int2[])[0:cardinality(c.conkey) - 1]) AS k)
                  = (SELECT array_agg(k ORDER BY k) FROM unnest(c.conkey) AS k))
        ORDER BY 1
        """);
    assertThat(uncovered).isEmpty();
    assertThat(one(Long.class, "SELECT count(*) FROM pg_constraint WHERE contype = 'f'"
        + " AND connamespace = 'public'::regnamespace")).isGreaterThanOrEqualTo(30L);
  }
}
