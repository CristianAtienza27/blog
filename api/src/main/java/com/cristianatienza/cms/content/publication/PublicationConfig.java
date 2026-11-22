package com.cristianatienza.cms.content.publication;

import com.cristianatienza.cms.security.audit.AuditLog;
import java.sql.DriverManager;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Servicio de publicación (fase 6.3.4). Existe en la API y en sus herramientas (mismo login, R1′);
 * sin {@code cms.publication.*} no está configurado y no hace nada.
 */
@Configuration
@EnableConfigurationProperties(PublicationProperties.class)
class PublicationConfig {

  @Bean
  PublicationService publicationService(DataSource dataSource, PublicationProperties properties, Environment environment,
      ObjectProvider<AuditLog> auditLog, ObjectProvider<PublicationService.Hooks> hooks) {
    WebActivationClient web = properties.webInternalUrl() == null ? null
        : new WebActivationClient(properties.webInternalUrl(), properties.webConnectTimeout(),
            properties.webRequestTimeout());
    return new PublicationService(dataSource, properties, web, new PublicationAudit(auditLog),
        lockConnections(environment), System::nanoTime, hooks.getIfAvailable(() -> PublicationService.Hooks.NONE),
        PublicationLock.Hooks.NONE);
  }

  /**
   * Conexiones del bloqueo: con el mismo login que la API pero FUERA del pool, para que cerrarlas
   * termine la sesión de PostgreSQL (y libere el bloqueo) en cualquier caso.
   */
  static PublicationLock.ConnectionFactory lockConnections(Environment environment) {
    String url = environment.getRequiredProperty("spring.datasource.url");
    String user = environment.getRequiredProperty("spring.datasource.username");
    String password = environment.getProperty("spring.datasource.password");
    return timeoutSeconds -> {
      Properties props = new Properties();
      props.setProperty("user", user);
      if (password != null) {
        props.setProperty("password", password);
      }
      props.setProperty("connectTimeout", Integer.toString(timeoutSeconds));
      props.setProperty("loginTimeout", Integer.toString(timeoutSeconds));
      props.setProperty("socketTimeout", "30");
      props.setProperty("tcpKeepAlive", "true");
      props.setProperty("ApplicationName", "cms-publicacion");
      return DriverManager.getConnection(url, props);
    };
  }
}
