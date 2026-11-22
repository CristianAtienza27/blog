package com.cristianatienza.cms.content.publication;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reconciliación al arrancar la API (fase 6.3.4). No bloquea el arranque ni afecta a readiness: si
 * PostgreSQL o el control no están disponibles, reintenta cada {@code reconcile-retry} hasta
 * conseguirlo. Una inconsistencia se registra (y se ve en el listado), nunca se resuelve sola.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class PublicationStartup {

  private static final Logger log = LoggerFactory.getLogger(PublicationStartup.class);

  private final PublicationService service;
  private final PublicationProperties properties;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
    Thread thread = new Thread(runnable, "publicacion-reconciliacion");
    thread.setDaemon(true);
    return thread;
  });

  PublicationStartup(PublicationService service, PublicationProperties properties) {
    this.service = service;
    this.properties = properties;
  }

  @EventListener(ApplicationReadyEvent.class)
  void onReady() {
    if (service.configured()) {
      scheduler.execute(this::attempt);
    }
  }

  private void attempt() {
    try {
      PublicationReconciler.Report report = service.reconcile();
      if (report.status() == PublicationReconciler.Status.INCONSISTENTE) {
        log.warn("Publicación: INCONSISTENTE ({}); se recupera activando una versión o publicando", report.reason());
      } else {
        log.info("Publicación: {} (versión activa: {})", report.status(),
            report.pointerVersion() == null ? "ninguna" : "v" + report.pointerVersion());
      }
    } catch (RuntimeException e) {
      log.warn("Publicación: no se pudo reconciliar ({}); reintento en {}", e.getMessage(), properties.reconcileRetry());
      scheduler.schedule(this::attempt, properties.reconcileRetry().toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  @PreDestroy
  void stop() {
    scheduler.shutdownNow();
  }
}
