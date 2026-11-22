package com.cristianatienza.cms.content.publication;

import com.cristianatienza.cms.content.publication.ActivePointer.Invalid;
import com.cristianatienza.cms.content.publication.ActivePointer.Missing;
import com.cristianatienza.cms.content.publication.ActivePointer.Valid;
import com.cristianatienza.cms.content.publication.PublicationRepository.Row;
import com.cristianatienza.cms.content.snapshot.SnapshotVerifier;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reconciliación de PostgreSQL con estado/activa.json (fase 6.3.4), dentro de una transacción
 * protegida (con el control y la época). activa.json manda, pero solo se aplican transiciones de la
 * matriz: cualquier otra situación queda registrada como INCONSISTENTE, sin cambiar ninguna fila y
 * sin inventar una versión activa. Es idempotente: cada transición es un UPDATE condicionado al
 * estado esperado, y la auditoría va en la misma transacción solo si hubo cambio (la de la
 * comprobación, solo cuando cambia su resultado).
 */
final class PublicationReconciler {

  enum Status { CONCILIADO, VACIO, INCONSISTENTE }

  /** Resultado: el estado, el motivo (si no está conciliado) y la versión del puntero. */
  record Report(Status status, String reason, Integer pointerVersion, List<String> transitions) {}

  private final PublicationAudit audit;

  PublicationReconciler(PublicationAudit audit) {
    this.audit = audit;
  }

  Report reconcile(Connection c, ActivePointer.Read pointer, Path snapshotsDir) throws SQLException {
    Actor actor = Actor.RECONCILIATION;
    List<String> transitions = new ArrayList<>();

    // 1. Generaciones abandonadas (con el control, ninguna «generando» está viva). Si su carpeta vN
    //    existe y es correcta (se renombró pero no llegó el COMMIT), se adopta con la huella del
    //    manifiesto; si no, fallida sin huella (nunca se podrá activar).
    for (Row row : PublicationRepository.inState(c, "generando")) {
      Path dir = snapshotsDir.resolve("v" + row.version());
      Optional<SnapshotVerifier.Summary> summary = Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
          ? SnapshotVerifier.summary(dir) : Optional.empty();
      if (summary.isPresent()) {
        SnapshotVerifier.Summary s = summary.get();
        PublicationRepository.toCandidate(c, row.version(), s.contentSha256(), s.files(), s.bytes(), s.articles());
        transitions.add("v" + row.version() + ": generando → candidata (carpeta completa)");
      } else if (PublicationRepository.toFailed(c, row.version(), List.of("generación interrumpida"))) {
        transitions.add("v" + row.version() + ": generando → fallida");
        audit.record(c, PublicationAudit.PUBLISH_FAILED, actor,
            Map.of("version", "v" + row.version(), "motivo", "generación interrumpida"));
      }
    }

    Report report;
    if (pointer instanceof Missing) {
      report = PublicationRepository.everActivated(c)
          ? inconsistent(c, "no hay activa.json, pero PostgreSQL registra versiones activadas", null, transitions)
          : new Report(Status.VACIO, null, null, transitions);
    } else if (pointer instanceof Invalid invalid) {
      report = inconsistent(c, "activa.json no válido (" + invalid.reason() + ")", null, transitions);
    } else {
      Valid valid = (Valid) pointer;
      report = withPointer(c, valid, transitions, actor);
      // Las candidatas que no son la del puntero no llegaron a activarse: fallidas con su huella.
      for (Row row : PublicationRepository.inState(c, "candidata")) {
        if (row.version() != valid.version()
            && PublicationRepository.toFailed(c, row.version(), List.of("interrumpida antes de activar"))) {
          transitions.add("v" + row.version() + ": candidata → fallida");
          audit.record(c, PublicationAudit.PUBLISH_FAILED, actor,
              Map.of("version", "v" + row.version(), "motivo", "interrumpida antes de activar"));
        }
      }
    }
    saveCheck(c, report, actor);
    return report;
  }

  private Report withPointer(Connection c, Valid pointer, List<String> transitions, Actor actor) throws SQLException {
    int v = pointer.version();
    Optional<Row> row = PublicationRepository.find(c, v);
    if (row.isEmpty()) {
      return inconsistent(c, "activa.json señala v" + v + ", que no tiene registro", v, transitions);
    }
    Row target = row.get();
    if (!pointer.contentSha256().equals(target.contentSha256())) {
      return inconsistent(c, "activa.json señala v" + v + " con una huella distinta de la registrada", v, transitions);
    }
    if ("activa".equals(target.state())) {
      return new Report(Status.CONCILIADO, null, v, transitions);
    }
    if (!target.activatable()) {
      return inconsistent(c, "activa.json señala v" + v + ", registrada como " + describe(target), v, transitions);
    }
    Optional<Optional<Integer>> previous = PublicationRepository.activate(c, v);
    if (previous.isPresent()) {
      transitions.add("v" + v + ": " + target.state() + " → activa");
      Map<String, String> details = new LinkedHashMap<>();
      details.put("version", "v" + v);
      details.put("contentSha256", target.contentSha256());
      previous.get().ifPresent(p -> details.put("anterior", "v" + p));
      audit.record(c, PublicationAudit.ACTIVATED, actor, details);
    }
    return new Report(Status.CONCILIADO, null, v, transitions);
  }

  private static String describe(Row row) {
    if ("fallida".equals(row.state())) {
      return row.contentSha256() == null ? "fallida sin huella" : "fallida con los archivos purgados";
    }
    return row.state();
  }

  private static Report inconsistent(Connection c, String reason, Integer pointerVersion, List<String> transitions) {
    return new Report(Status.INCONSISTENTE, reason, pointerVersion, transitions);
  }

  /** Guarda el resultado; solo si cambia, lo audita (repetir no duplica eventos). */
  private void saveCheck(Connection c, Report report, Actor actor) throws SQLException {
    PublicationRepository.Check previous = PublicationRepository.check(c);
    boolean changed = !report.status().name().equals(previous.status())
        || !java.util.Objects.equals(report.reason(), previous.reason())
        || !java.util.Objects.equals(report.pointerVersion(), previous.pointerVersion());
    PublicationRepository.saveCheck(c, report.status().name(), report.reason(), report.pointerVersion());
    if (!changed) {
      return;
    }
    Map<String, String> details = new LinkedHashMap<>();
    details.put("estado", report.status().name());
    if (report.reason() != null) {
      details.put("motivo", report.reason());
    }
    if (report.pointerVersion() != null) {
      details.put("puntero", "v" + report.pointerVersion());
    }
    audit.record(c, report.status() == Status.INCONSISTENTE
        ? PublicationAudit.RECONCILIATION_FAILED : PublicationAudit.RECONCILED, actor, details);
  }
}
