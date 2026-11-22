package com.cristianatienza.cms.security.tool;

import com.cristianatienza.cms.CmsApplication;
import com.cristianatienza.cms.content.io.ContentTool;
import com.cristianatienza.cms.security.PasswordHashing;
import com.cristianatienza.cms.testsupport.TestDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Acceso de los tests (de otros paquetes) a la herramienta de seguridad: la herramienta completa,
 * como en su contenedor (Spring sin servidor web, conectada con un login real), o la recuperación
 * directamente, para fijar el orden frente a la API en los tests de concurrencia. Nunca con el
 * superusuario.
 */
public final class ToolTestAccess {

  public static final String OPERATOR = "operadora@equipo-de-pruebas";
  private static final PasswordHashing HASHING = new PasswordHashing(1, Duration.ofSeconds(30));

  private ToolTestAccess() {}

  /**
   * La herramienta completa con ese login y esos argumentos; devuelve el código de salida. Además
   * comprueba que la herramienta de contenido no se activa con las órdenes de seguridad (H2).
   */
  public static int run(String login, String... args) {
    List<String> properties = new ArrayList<>(List.of("CMS_DB_URL=" + TestDatabase.url(), "CMS_DB_USER=" + login,
        "CMS_DB_PASSWORD=" + TestDatabase.password(login), "cms.tool.system-exit=false", "CMS_OPERATOR=" + OPERATOR));
    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CmsApplication.class)
        .web(WebApplicationType.NONE)
        .properties(properties.toArray(String[]::new))
        .run(args)) {
      if (context.getBeanProvider(ContentTool.class).getIfAvailable() != null) {
        throw new AssertionError("ContentTool no debe activarse con una orden de la herramienta de seguridad");
      }
      return context.getBean(SecurityTool.class).lastExitCode();
    }
  }

  /** Recuento de lo que ha hecho una recuperación. */
  public record Recovered(UUID userId, int passkeys, int userHandles, int codes, int devices, int sessions) {}

  /** La recuperación de emergencia directamente, como cms_security_tool. */
  public static Recovered recover(String email, Path newPasswordFile) throws SQLException {
    return recover(email, newPasswordFile, new ToolAudit("equipo-de-pruebas", OPERATOR));
  }

  /** La recuperación con una auditoría que no se puede escribir: no debe cambiar nada. */
  public static Recovered recoverWithFailingAudit(String email, Path newPasswordFile) throws SQLException {
    return recover(email, newPasswordFile, new ToolAudit("equipo-de-pruebas", OPERATOR) {
      @Override
      void record(Connection connection, String event, String tool, UUID userId, Map<String, String> details)
          throws SQLException {
        super.record(connection, event, tool, userId, details);
        throw new SQLException("fallo simulado de la auditoría", "58030");
      }
    });
  }

  private static Recovered recover(String email, Path file, ToolAudit audit) throws SQLException {
    var r = new EmergencyRecovery(securityToolDataSource(), HASHING, new PasswordPolicy(), audit).run(email, file);
    return new Recovered(r.userId(), r.passkeys(), r.userHandles(), r.codes(), r.devices(), r.sessions());
  }

  private static DataSource securityToolDataSource() {
    return new DriverManagerDataSource(TestDatabase.url(), "cms_security_tool", TestDatabase.password("cms_security_tool"));
  }
}
