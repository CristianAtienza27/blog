package com.cristianatienza.cms.content.publication;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de la publicación (fase 6.3.4). Sin las tres primeras, la publicación no está
 * configurada: los endpoints responden 503 y no se reconcilia.
 *
 * @param snapshotsDir carpeta de instantáneas (la API escribe)
 * @param stateDir carpeta estado/ de la web (la API solo lee activa.json)
 * @param webInternalUrl puerto interno de la web (http://IP:puerto)
 * @param lockWait espera máxima para tomar el control (global: conexión, bloqueo y época)
 * @param lockPoll intervalo entre intentos de bloqueo
 * @param lockHeartbeat intervalo del heartbeat de la sesión del bloqueo
 * @param lockIdleTimeout idle_session_timeout de esa sesión (al menos 3 heartbeats)
 * @param webConnectTimeout conexión con la web
 * @param webRequestTimeout respuesta de la web (una activación bajo carga tarda segundos)
 * @param reconcileRetry reintento de la reconciliación al arrancar si PostgreSQL no está
 * @param keep versiones más recientes que la purga nunca borra (además de la activa y la anterior)
 * @param listLimit publicaciones que devuelve el listado
 */
@ConfigurationProperties("cms.publication")
public record PublicationProperties(
    Path snapshotsDir,
    Path stateDir,
    URI webInternalUrl,
    @DefaultValue("15s") Duration lockWait,
    @DefaultValue("200ms") Duration lockPoll,
    @DefaultValue("10s") Duration lockHeartbeat,
    @DefaultValue("60s") Duration lockIdleTimeout,
    @DefaultValue("2s") Duration webConnectTimeout,
    @DefaultValue("30s") Duration webRequestTimeout,
    @DefaultValue("30s") Duration reconcileRetry,
    @DefaultValue("20") int keep,
    @DefaultValue("50") int listLimit) {

  public PublicationProperties {
    if (lockIdleTimeout.compareTo(lockHeartbeat.multipliedBy(3)) < 0) {
      throw new IllegalArgumentException(
          "cms.publication.lock-idle-timeout debe ser al menos 3 veces cms.publication.lock-heartbeat");
    }
    if (keep < 0 || listLimit < 1) {
      throw new IllegalArgumentException("cms.publication.keep y list-limit no válidos");
    }
  }

  public boolean configured() {
    return snapshotsDir != null && stateDir != null && webInternalUrl != null;
  }
}
