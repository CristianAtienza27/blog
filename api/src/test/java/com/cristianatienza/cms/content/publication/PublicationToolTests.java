package com.cristianatienza.cms.content.publication;

import static org.assertj.core.api.Assertions.assertThat;

import com.cristianatienza.cms.CmsApplication;
import com.cristianatienza.cms.content.io.ContentTool;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Las herramientas de publicación (fase 6.3.4, R1′), completas y en el mismo proceso, con el login
 * real de la API: registran solo hechos verificables (session_user y la máquina) e ignoran
 * cualquier operador declarado.
 */
@ExtendWith(OutputCaptureExtension.class)
class PublicationToolTests extends PublicationTestSupport {

  private int tool(String... args) {
    List<String> properties = new ArrayList<>(List.of("CMS_DB_URL=" + POSTGRES.getJdbcUrl(), "CMS_DB_USER=" + APP_LOGIN,
        "CMS_DB_PASSWORD=" + TestDatabase.password(APP_LOGIN), "cms.tool.system-exit=false",
        "cms.publication.snapshots-dir=" + snapshots, "cms.publication.state-dir=" + state,
        "cms.publication.web-internal-url=" + web.uri(),
        // Un intento de suplantación: la herramienta no lo usa.
        "CMS_OPERATOR=persona-suplantada"));
    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CmsApplication.class)
        .web(WebApplicationType.NONE).properties(properties.toArray(String[]::new)).run(args)) {
      assertThat(context.getBeanProvider(ContentTool.class).getIfAvailable()).isNull();
      return context.getBean(PublicationTool.class).lastExitCode();
    }
  }

  @Test
  void publicarActivarYPurgarConElLoginDeLaApi(CapturedOutput output) throws Exception {
    assertThat(tool("--cms.tool=publicar")).isZero();
    assertThat(output.getOut()).contains("Resultado: activada (v1)");
    assertThat(states()).isEqualTo("v1=activa");
    // Quién: el login autenticado por PostgreSQL y la máquina; ni rastro del operador declarado.
    assertThat(column("SELECT details ->> 'dbUser' FROM audit_event WHERE event = 'content.published'"))
        .containsExactly(APP_LOGIN);
    assertThat(column("SELECT (details ->> 'host') IS NOT NULL FROM audit_event WHERE event = 'content.published'"))
        .containsExactly("t");
    assertThat(column("SELECT details ->> 'via' FROM audit_event WHERE event = 'content.published'"))
        .containsExactly("herramienta");
    assertThat(one(Long.class, "SELECT count(*) FROM audit_event WHERE details::text LIKE '%suplantada%'"
        + " OR details ->> 'declaredOperator' IS NOT NULL")).isZero();
    assertThat(one(String.class, "SELECT requested_via FROM content_publication WHERE version = 1"))
        .isEqualTo("herramienta");
    assertThat(one(String.class, "SELECT requested_by::text FROM content_publication WHERE version = 1")).isNull();

    // Sin cambios: código 0. Activar sin versión: error. La purga, sin confirmar, solo simula.
    assertThat(tool("--cms.tool=publicar")).isZero();
    assertThat(output.getOut()).contains("Resultado: sin cambios");
    assertThat(tool("--cms.tool=activar")).isEqualTo(1);
    assertThat(tool("--cms.tool=activar", "--cms.version=v9")).isEqualTo(1);
    assertThat(output.getOut()).contains("version_not_found");
    assertThat(tool("--cms.tool=purgar-instantaneas")).isZero();
    assertThat(output.getOut()).contains("Simulación de purga");
  }
}
