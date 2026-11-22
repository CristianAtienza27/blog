package com.cristianatienza.cms.security.passkey;

import com.cristianatienza.cms.security.SecretKeys;
import com.cristianatienza.cms.security.audit.AuditLog;
import com.cristianatienza.cms.security.audit.CsrfRejections;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/** Passkeys y recuperación (fase 6.2.3). Solo en la API web, no en las herramientas de línea de comandos. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(WebAuthnProperties.class)
@EnableScheduling
public class PasskeyConfig {

  @Bean
  @ConditionalOnMissingBean
  CeremonyObserver ceremonyObserver() {
    return CeremonyObserver.NONE;
  }

  /** Auditoría persistente (6.2.4): audit_event y audit_event_ip, cada evento en su transacción. */
  @Bean
  AuditLog securityEvents(DataSource dataSource, PlatformTransactionManager transactions, SecretKeys keys) {
    return new AuditLog(dataSource, transactions, keys);
  }

  /** Rechazos CSRF agregados por IP y minuto (E5). */
  @Bean
  CsrfRejections csrfRejections(AuditLog audit, SecretKeys keys) {
    return new CsrfRejections(audit, keys);
  }

  @Bean
  WebAuthnJson webAuthnJson() {
    return new WebAuthnJson();
  }

  @Bean
  ChallengeStore challengeStore(DataSource dataSource, PlatformTransactionManager transactions, SecretKeys keys,
      WebAuthnProperties properties, CeremonyObserver observer) {
    return new ChallengeStore(dataSource, transactions, keys, properties, observer);
  }

  @Bean
  PasskeyStore passkeyStore(DataSource dataSource, PlatformTransactionManager transactions, CeremonyObserver observer) {
    return new PasskeyStore(dataSource, transactions, observer);
  }

  @Bean
  PasskeyCeremonies passkeyCeremonies(WebAuthnProperties properties, ChallengeStore challenges, PasskeyStore passkeys,
      WebAuthnJson json) {
    return new PasskeyCeremonies(properties, challenges, passkeys, json);
  }
}
