package com.cristianatienza.cms.security.tool;

import com.cristianatienza.cms.security.PasswordHashing;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Herramienta de seguridad (fase 6.2.6): solo línea de comandos, sin servidor web
 * ({@code --spring.main.web-application-type=none}) y conectada como cms_security_tool. Ningún
 * endpoint HTTP llega a esta lógica.
 *
 * <pre>
 *   --cms.tool=crear-admin              --cms.usuario=&lt;correo&gt; --cms.contrasena-desde=&lt;archivo&gt;
 *   --cms.tool=recuperacion-emergencia  --cms.usuario=&lt;correo&gt; --cms.nueva-contrasena-desde=&lt;archivo&gt;
 * </pre>
 *
 * La contraseña llega siempre de un archivo, nunca de un argumento. La salida (H6) solo lleva el id
 * del usuario, el resultado y los recuentos: nunca la contraseña, el contenido del archivo, tokens
 * ni el correo. Código de salida: 0 correcto; 2 argumentos o contraseña no válidos; 3 usuario
 * inexistente (recuperación) o ya existente (alta); 1 error inesperado.
 */
@Component
@ConditionalOnExpression("'${cms.tool:}' == 'crear-admin' or '${cms.tool:}' == 'recuperacion-emergencia'")
public class SecurityTool implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SecurityTool.class);

  private final DataSource dataSource;
  private final PasswordHashing hashing;
  private final ConfigurableApplicationContext context;
  private final Environment environment;
  private final String tool;
  private final boolean systemExit;
  private volatile int lastExitCode = -1;

  public SecurityTool(DataSource dataSource, PasswordHashing hashing, ConfigurableApplicationContext context,
      Environment environment, @Value("${cms.tool}") String tool,
      @Value("${cms.tool.system-exit:true}") boolean systemExit) {
    this.dataSource = dataSource;
    this.hashing = hashing;
    this.context = context;
    this.environment = environment;
    this.tool = tool;
    this.systemExit = systemExit;
  }

  /** Código de salida de la última ejecución (para los tests, con cms.tool.system-exit=false). */
  public int lastExitCode() {
    return lastExitCode;
  }

  @Override
  public void run(ApplicationArguments args) {
    PrintStream out = System.out;
    int code;
    try {
      refuseInlinePasswords();
      requireSecurityToolLogin();
      PasswordPolicy policy = new PasswordPolicy();
      ToolAudit audit = ToolAudit.of(environment.getProperty("CMS_OPERATOR"));
      String email = environment.getProperty("cms.usuario", "");
      code = switch (tool) {
        case CreateAdmin.TOOL -> {
          var result = new CreateAdmin(dataSource, hashing, policy, audit)
              .run(email, file("cms.contrasena-desde"));
          out.println("Administrador creado: " + result.userId() + ". En el primer acceso, tras la contraseña,"
              + " solo podrá registrar una passkey (P1). Borra ahora el archivo de la contraseña.");
          yield 0;
        }
        case EmergencyRecovery.TOOL -> {
          var r = new EmergencyRecovery(dataSource, hashing, policy, audit)
              .run(email, file("cms.nueva-contrasena-desde"));
          out.println("Recuperación de emergencia completada para el usuario " + r.userId() + ": passkeys borradas "
              + r.passkeys() + ", identificador WebAuthn borrado " + r.userHandles() + ", códigos invalidados "
              + r.codes() + ", dispositivos revocados " + r.devices() + ", sesiones cerradas " + r.sessions() + ".");
          out.println("El siguiente acceso exige la contraseña nueva y registrar una passkey (P1)."
              + " Borra ahora el archivo de la contraseña.");
          yield 0;
        }
        default -> throw new ToolFailure(ToolFailure.INVALID, "herramienta desconocida");
      };
    } catch (PasswordPolicy.Violation e) {
      out.println("Error: " + e.getMessage() + "; no se ha cambiado nada");
      code = ToolFailure.INVALID;
    } catch (ToolFailure e) {
      out.println("Error: " + e.getMessage());
      code = e.exitCode;
    } catch (SQLException | RuntimeException e) {
      // Sin el mensaje: podría llevar valores de la fila (como el correo). Solo la clase y el estado.
      String state = e instanceof SQLException sql ? " (SQLSTATE " + sql.getSQLState() + ")" : "";
      log.error("la herramienta de seguridad ha fallado: {}{}", e.getClass().getSimpleName(), state);
      out.println("Error inesperado: " + e.getClass().getSimpleName() + state + "; la transacción se ha deshecho");
      code = 1;
    }
    int exitCode = code;
    lastExitCode = exitCode;
    if (systemExit) {
      System.exit(SpringApplication.exit(context, () -> exitCode));
    }
  }

  /** Una contraseña en un argumento ya ha quedado expuesta en la lista de procesos: se rechaza. */
  private void refuseInlinePasswords() {
    for (String property : new String[] {"cms.contrasena", "cms.nueva-contrasena", "cms.password"}) {
      if (environment.containsProperty(property)) {
        throw new ToolFailure(ToolFailure.INVALID, "la contraseña no se acepta como argumento: usa un archivo"
            + " (--cms.contrasena-desde o --cms.nueva-contrasena-desde) y cámbiala, porque ya ha quedado expuesta");
      }
    }
  }

  /** Solo como cms_security_tool (miembro de cms_security_admin), nunca como superusuario. */
  private void requireSecurityToolLogin() {
    Boolean ok = new JdbcTemplate(dataSource).queryForObject("SELECT pg_has_role(session_user, 'cms_security_admin',"
        + " 'USAGE') AND NOT (SELECT rolsuper FROM pg_roles WHERE rolname = session_user)", Boolean.class);
    if (!Boolean.TRUE.equals(ok)) {
      throw new ToolFailure(1, "la herramienta de seguridad debe conectarse como cms_security_tool (nunca como"
          + " superusuario); no se ha cambiado nada");
    }
  }

  private Path file(String property) {
    String value = environment.getProperty(property, "");
    if (value.isBlank()) {
      throw new ToolFailure(ToolFailure.INVALID, "falta --" + property + "=<archivo>");
    }
    return Path.of(value);
  }
}
