package com.cristianatienza.cms.security.audit;

import com.cristianatienza.cms.security.SecretKeys;
import jakarta.annotation.PreDestroy;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Rechazos CSRF agregados por IP y minuto (E5), en memoria de cada instancia, sin infraestructura
 * adicional.
 *
 * <ul>
 *   <li><b>Capacidad:</b> como mucho {@link #MAX_IPS} IP distintas por minuto (por su HMAC). Al
 *       llenarse, las IP nuevas se suman a un único contador de desbordamiento: el mapa no crece.
 *   <li><b>Sin bloquear:</b> registrar es O(1), sin bloqueos ni E/S en el hilo de la petición; la
 *       respuesta 403 es la misma esté como esté el mapa.
 *   <li><b>Cada minuto:</b> el mapa se sustituye de forma atómica por uno vacío y el lleno se
 *       escribe como un evento por IP (con su recuento) y, si lo hay, uno de desbordamiento.
 *   <li><b>Pérdidas aceptadas (documentadas):</b> hasta un minuto si la instancia se detiene sin
 *       vaciar; el detalle por IP por encima de la capacidad (solo queda el total); y el minuto
 *       entero si su escritura falla (se registra sin datos).
 * </ul>
 */
public class CsrfRejections {

  static final int MAX_IPS = 1000;
  private static final Logger log = LoggerFactory.getLogger(CsrfRejections.class);

  /** Un minuto de rechazos: por IP (con la IP para la tabla aparte) y el desbordamiento. */
  static final class Window {
    final Map<String, Entry> byIp = new ConcurrentHashMap<>();
    final LongAdder overflow = new LongAdder();
  }

  static final class Entry {
    final String ip;
    final LongAdder count = new LongAdder();

    Entry(String ip) {
      this.ip = ip;
    }
  }

  private final AtomicReference<Window> current = new AtomicReference<>(new Window());
  private final AuditLog audit;
  private final SecretKeys keys;

  public CsrfRejections(AuditLog audit, SecretKeys keys) {
    this.audit = audit;
    this.keys = keys;
  }

  /** Cuenta un rechazo. Nunca lanza ni bloquea. */
  public void record(String ip) {
    try {
      Window window = current.get();
      String key = HexFormat.of().formatHex(keys.origin("ip", ip == null ? "" : ip));
      Entry entry = window.byIp.get(key);
      if (entry == null) {
        if (window.byIp.size() >= MAX_IPS) {
          window.overflow.increment();
          return;
        }
        entry = window.byIp.computeIfAbsent(key, k -> new Entry(ip));
      }
      entry.count.increment();
    } catch (RuntimeException e) {
      // Nunca afecta a la petición.
    }
  }

  /** Vacía el minuto: un evento por IP y, si lo hubo, uno de desbordamiento. */
  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
  public void flush() {
    Window window = current.getAndSet(new Window());
    try {
      for (Entry entry : window.byIp.values()) {
        audit.write("security.csrf_rejected", "failure", null, Map.of("count", Long.toString(entry.count.sum())),
            entry.ip, null, null);
      }
      long overflow = window.overflow.sum();
      if (overflow > 0) {
        audit.write("security.csrf_rejected_overflow", "failure", null,
            Map.of("count", Long.toString(overflow), "capacity", Integer.toString(MAX_IPS)), null, null, null);
      }
    } catch (RuntimeException e) {
      log.error("rechazos CSRF de un minuto no registrados (fallo de la auditoría)");
    }
  }

  /** Al detenerse, intenta vaciar lo pendiente (sin garantía). */
  @PreDestroy
  void shutdown() {
    flush();
  }

  int trackedIps() {
    return current.get().byIp.size();
  }
}
