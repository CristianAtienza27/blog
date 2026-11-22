package com.cristianatienza.cms.content.publication;

import java.io.PrintStream;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Herramientas de publicación (fase 6.3.4), con el login de la API (cms_api, rol cms_app: los
 * mismos privilegios que la vía HTTP, R1′). Sin servidor web:
 *
 * <pre>
 *   --cms.tool=publicar
 *   --cms.tool=activar --cms.version=vN
 *   --cms.tool=purgar-instantaneas [--cms.confirmar=true]   (sin confirmar, solo simula)
 * </pre>
 *
 * <p>Quién: solo hechos verificables, el login de PostgreSQL de la sesión ({@code session_user}) y
 * la máquina. No acepta un operador declarado (ni CMS_OPERATOR ni parámetros).
 *
 * <p>Código de salida: 0 correcto (activada, sin cambios, purga); 2 rechazada o fallida; 1 error.
 */
@Component
@ConditionalOnExpression("'${cms.tool:}' == 'publicar' or '${cms.tool:}' == 'activar'"
    + " or '${cms.tool:}' == 'purgar-instantaneas'")
class PublicationTool implements ApplicationRunner {

  private static final Pattern VERSION = Pattern.compile("v([1-9][0-9]{0,8})");

  private final PublicationService service;
  private final DataSource dataSource;
  private final ConfigurableApplicationContext context;
  private final String tool;
  private final String version;
  private final boolean confirm;
  private final boolean systemExit;
  private volatile int lastExitCode = -1;

  PublicationTool(PublicationService service, DataSource dataSource, ConfigurableApplicationContext context,
      @Value("${cms.tool}") String tool, @Value("${cms.version:}") String version,
      @Value("${cms.confirmar:false}") boolean confirm, @Value("${cms.tool.system-exit:true}") boolean systemExit) {
    this.service = service;
    this.dataSource = dataSource;
    this.context = context;
    this.tool = tool;
    this.version = version;
    this.confirm = confirm;
    this.systemExit = systemExit;
  }

  int lastExitCode() {
    return lastExitCode;
  }

  @Override
  public void run(ApplicationArguments args) {
    PrintStream out = System.out;
    int code;
    try {
      Actor actor = Actor.tool(sessionUser(), hostName());
      code = switch (tool) {
        case "publicar" -> report(out, service.publish(actor));
        case "activar" -> {
          Matcher matcher = VERSION.matcher(version);
          if (!matcher.matches()) {
            out.println("Error: falta --cms.version=vN (o no es válida)");
            yield 1;
          }
          yield report(out, service.activate(Integer.parseInt(matcher.group(1)), actor));
        }
        default -> purge(out, service.purge(confirm, actor));
      };
    } catch (PublicationFailure e) {
      out.println("Error (" + e.kind().code + "): " + e.getMessage());
      code = 1;
    }
    int exitCode = code;
    lastExitCode = exitCode;
    if (systemExit) {
      System.exit(SpringApplication.exit(context, () -> exitCode));
    }
  }

  private static int report(PrintStream out, PublicationService.Outcome outcome) {
    out.println("Resultado: " + outcome.result() + (outcome.version() == null ? "" : " (v" + outcome.version() + ")")
        + (outcome.previous() == null ? "" : ", anterior v" + outcome.previous()));
    if (outcome.contentSha256() != null) {
      out.println("contentSha256: " + outcome.contentSha256());
    }
    outcome.issues().forEach(issue -> out.println("  - " + issue));
    return switch (outcome.result()) {
      case PublicationService.RESULT_ACTIVATED, PublicationService.RESULT_UNCHANGED -> 0;
      default -> 2;
    };
  }

  private static int purge(PrintStream out, PublicationService.PurgeReport report) {
    out.println((report.confirmed() ? "Purga" : "Simulación de purga (sin --cms.confirmar=true no se borra nada)")
        + ": versiones que se pueden purgar " + report.eligible());
    if (report.confirmed()) {
      out.println("Purgadas: " + report.purged());
    }
    return 0;
  }

  /** El login de PostgreSQL de la sesión: autenticado por PostgreSQL, no declarado. */
  private String sessionUser() {
    try (Connection c = dataSource.getConnection();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT session_user")) {
      rs.next();
      return rs.getString(1);
    } catch (SQLException e) {
      throw new PublicationFailure(PublicationFailure.Kind.DATABASE, "no se puede conectar con PostgreSQL", e);
    }
  }

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return System.getenv("HOSTNAME");
    }
  }
}
