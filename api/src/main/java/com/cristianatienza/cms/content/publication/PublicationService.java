package com.cristianatienza.cms.content.publication;

import com.cristianatienza.cms.content.io.ContentException;
import com.cristianatienza.cms.content.io.ContentExporter;
import com.cristianatienza.cms.content.io.ContentSource.Content;
import com.cristianatienza.cms.content.publication.ActivePointer.Valid;
import com.cristianatienza.cms.content.publication.PublicationFailure.Kind;
import com.cristianatienza.cms.content.publication.PublicationReconciler.Report;
import com.cristianatienza.cms.content.publication.PublicationReconciler.Status;
import com.cristianatienza.cms.content.publication.PublicationRepository.Row;
import com.cristianatienza.cms.content.snapshot.SnapshotFormat;
import com.cristianatienza.cms.content.snapshot.SnapshotWriter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;

/**
 * Publicación del estado publicado y activación de versiones (fase 6.3.4).
 *
 * <p><b>Publicar.</b> Tomar el control (bloqueo + época) → reservar la época en la web (pronto, para
 * cerrar el paso a peticiones antiguas) → reconciliar → leer lo publicado y calcular la huella («sin
 * cambios» solo si está CONCILIADO y coincide con la activa) → P1: fila «generando» → generar en
 * .tmp → P2: con la fila de la época bloqueada, «candidata» con la huella Y renombrado a vN, en la
 * misma transacción → P3/P4: con la fila de la época bloqueada, reservar (recuperar la época si la
 * web está bloqueada), activar en la web y registrar el resultado con su auditoría → reconciliar.
 * Toda transacción que cambia el estado está protegida por la época ({@link PublicationControl}).
 */
public class PublicationService {

  /** Resultado de publicar o activar. */
  public record Outcome(String result, Integer version, String state, String contentSha256, Integer previous,
      List<String> issues) {}

  /** Lo que muestra el listado. */
  public record Overview(boolean configured, String reconciliation, String reason, Integer pointerVersion,
      Instant checkedAt, Long epoch, List<Row> publications) {}

  /** Resultado de la purga. */
  public record PurgeReport(boolean confirmed, List<Integer> eligible, List<Integer> purged) {}

  /** Puntos de prueba (solo los tests los usan): pausan o fallan en un punto del flujo. */
  @FunctionalInterface
  public interface Hooks {
    void at(String point) throws Exception;

    Hooks NONE = point -> {};
  }

  public static final String RESULT_ACTIVATED = "activada";
  public static final String RESULT_UNCHANGED = "sin cambios";
  public static final String RESULT_REJECTED = "rechazada";
  public static final String RESULT_FAILED = "fallida";

  private final DataSource dataSource;
  private final PublicationProperties properties;
  private final WebActivationClient web;
  private final PublicationAudit audit;
  private final PublicationReconciler reconciler;
  private final PublicationLock.ConnectionFactory connections;
  private final LongSupplier clock;
  private final Hooks hooks;
  private final PublicationLock.Hooks lockHooks;

  PublicationService(DataSource dataSource, PublicationProperties properties, WebActivationClient web,
      PublicationAudit audit, PublicationLock.ConnectionFactory connections, LongSupplier clock, Hooks hooks,
      PublicationLock.Hooks lockHooks) {
    this.dataSource = dataSource;
    this.properties = properties;
    this.web = web;
    this.audit = audit;
    this.reconciler = new PublicationReconciler(audit);
    this.connections = connections;
    this.clock = clock;
    this.hooks = hooks;
    this.lockHooks = lockHooks;
  }

  public boolean configured() {
    return properties.configured();
  }

  // --- Publicar ------------------------------------------------------------------------------------

  public Outcome publish(Actor actor) {
    requireConfigured();
    SnapshotWriter writer = SnapshotWriter.at(properties.snapshotsDir());
    try (PublicationControl control = take(actor)) {
      reserveEarly(control);
      Report report = reconcileWithin(control, writer);
      Content content = readPublished();
      String sha = SnapshotWriter.contentSha256(content);
      ActivePointer.Read pointer = ActivePointer.read(properties.stateDir());
      if (report.status() == Status.CONCILIADO && pointer instanceof Valid valid && valid.contentSha256().equals(sha)) {
        return new Outcome(RESULT_UNCHANGED, valid.version(), "activa", sha, null, List.of());
      }

      int version = control.fenced("p1", c -> {
        int next = Math.max(PublicationRepository.maxVersion(c), folderMax(writer)) + 1;
        PublicationRepository.insertGenerating(c, next, actor.userId(), actor.via());
        return next;
      });
      SnapshotWriter.Prepared prepared;
      try {
        prepared = writer.prepare(content, "v" + version);
      } catch (RuntimeException e) {
        failGeneration(control, version, actor, "no se pudo generar");
        throw e;
      }
      try {
        hook("generacion");
        control.ensureHeld();
      } catch (RuntimeException e) {
        // Sin el control (o con un error) no se renombra nada: se descarta la temporal.
        writer.discard(prepared);
        failGeneration(control, version, actor, "interrumpida durante la generación");
        throw e;
      }
      try {
        control.fenced("p2", c -> {
          PublicationRepository.toCandidate(c, version, prepared.snapshot().contentSha256(),
              prepared.snapshot().files(), prepared.snapshot().bytes(), prepared.snapshot().articles());
          writer.commit(prepared); // renombrado DENTRO de la transacción: si falla, se deshace
          hook("p2.antesDelCommit");
          return null;
        });
      } catch (RuntimeException e) {
        // La transacción se deshizo. Si el renombrado no llegó a hacerse, se descarta la temporal y
        // la fila pasa a fallida sin huella; si vN existe, la reconciliación la adoptará.
        if (!Files.exists(prepared.target(), LinkOption.NOFOLLOW_LINKS)) {
          writer.discard(prepared);
          failGeneration(control, version, actor, "no se pudo completar la generación");
        }
        throw e;
      }
      hook("p2.despues");
      Outcome outcome = activateInWeb(control, version, actor, true);
      reconcileWithin(control, writer);
      return outcome;
    }
  }

  // --- Activar una versión existente (volver atrás) ---------------------------------------------------

  public Outcome activate(int version, Actor actor) {
    requireConfigured();
    SnapshotWriter writer = SnapshotWriter.at(properties.snapshotsDir());
    try (PublicationControl control = take(actor)) {
      reserveEarly(control);
      Report report = reconcileWithin(control, writer);
      Row row = read(c -> PublicationRepository.find(c, version))
          .orElseThrow(() -> new PublicationFailure(Kind.NOT_FOUND, "la versión v" + version + " no existe"));
      if ("activa".equals(row.state()) && report.status() == Status.CONCILIADO) {
        return new Outcome(RESULT_UNCHANGED, version, "activa", row.contentSha256(), row.previousVersion(), List.of());
      }
      if (!"activa".equals(row.state()) && !row.activatable()) {
        throw new PublicationFailure(Kind.NOT_ACTIVATABLE,
            "la versión v" + version + " no se puede activar (" + row.state() + ")");
      }
      Outcome outcome = activateInWeb(control, version, actor, false);
      reconcileWithin(control, writer);
      return outcome;
    }
  }

  /**
   * P3/P4 en UNA transacción protegida: la fila de la época queda bloqueada mientras se llama a la
   * web, así que nadie puede tomar el control a la vez; si el publicador pierde la conexión, la
   * transacción se deshace y la web (con la época) rechaza cualquier petición suya que llegue tarde.
   */
  private Outcome activateInWeb(PublicationControl control, int version, Actor actor, boolean publishing) {
    return control.fenced("p3", c -> {
      Optional<Row> before = PublicationRepository.find(c, version);
      String sha = before.map(Row::contentSha256).orElse(null);
      WebActivationClient.Response response;
      try {
        ensureReservation(c, control, actor);
        hook("p3.antesDeLaWeb");
        response = web.activate(version, control.epoch());
      } catch (PublicationFailure e) {
        if (e.kind() != Kind.WEB_UNAVAILABLE) {
          throw e;
        }
        return webUnavailable(c, version, actor, sha, e.getMessage());
      }
      hook("p3.despuesDeLaWeb");
      switch (response.status()) {
        case 200 -> {
          Optional<Optional<Integer>> previous = PublicationRepository.activate(c, version);
          Map<String, String> details = new LinkedHashMap<>();
          details.put("version", "v" + version);
          details.put("contentSha256", sha);
          details.put("epoca", Long.toString(control.epoch()));
          previous.flatMap(p -> p).ifPresent(p -> details.put("anterior", "v" + p));
          audit.record(c, publishing ? PublicationAudit.PUBLISHED : PublicationAudit.ACTIVATED, actor, details);
          return new Outcome(RESULT_ACTIVATED, version, "activa", sha, previous.flatMap(p -> p).orElse(null), List.of());
        }
        case 422 -> {
          List<String> issues = issues(response.body());
          boolean rejected = PublicationRepository.toRejected(c, version, issues);
          Map<String, String> details = new LinkedHashMap<>();
          details.put("version", "v" + version);
          details.put("incidencias", String.join(" | ", issues));
          audit.record(c, rejected ? PublicationAudit.PUBLISH_REJECTED : PublicationAudit.ACTIVATION_REJECTED,
              actor, details);
          return new Outcome(RESULT_REJECTED, version, rejected ? "rechazada" : before.map(Row::state).orElse(null),
              sha, null, issues);
        }
        case 409 -> throw new PublicationFailure(Kind.FENCED,
            "la web tiene una época más reciente: otro publicador ha tomado el control");
        default -> throw new PublicationFailure(Kind.WEB_UNAVAILABLE,
            "respuesta inesperada de la web (" + response.status() + ")");
      }
    });
  }

  /** La web no respondió: manda activa.json. Si ya sirve vN, se registra; si no, vN queda fallida. */
  private Outcome webUnavailable(Connection c, int version, Actor actor, String sha, String reason) throws SQLException {
    ActivePointer.Read pointer = ActivePointer.read(properties.stateDir());
    if (pointer instanceof Valid valid && valid.version() == version && valid.contentSha256().equals(sha)) {
      Optional<Optional<Integer>> previous = PublicationRepository.activate(c, version);
      Map<String, String> details = new LinkedHashMap<>();
      details.put("version", "v" + version);
      details.put("motivo", "la web no respondió, pero activa.json ya la señala");
      audit.record(c, PublicationAudit.ACTIVATED, actor, details);
      return new Outcome(RESULT_ACTIVATED, version, "activa", sha, previous.flatMap(p -> p).orElse(null), List.of());
    }
    List<String> issues = List.of("la web no respondió: " + reason);
    if (PublicationRepository.toFailed(c, version, issues)) {
      audit.record(c, PublicationAudit.PUBLISH_FAILED, actor, Map.of("version", "v" + version, "motivo", issues.get(0)));
    }
    return new Outcome(RESULT_FAILED, version, PublicationRepository.find(c, version).map(Row::state).orElse(null),
        sha, null, issues);
  }

  /**
   * Reserva la época en la web. Si la web está bloqueada (control.json dañado: 423), la recupera en
   * dos pasos con el nonce que da la propia web y la época autoritativa de PostgreSQL. Se llama
   * DENTRO de la transacción protegida (con el control).
   */
  private void ensureReservation(Connection c, PublicationControl control, Actor actor) throws SQLException {
    WebActivationClient.Response reserved = web.reserve(control.epoch());
    if (reserved.status() == 423) {
      WebActivationClient.Response begun = web.beginRecovery();
      String nonce = begun.body().path("nonce").asString("");
      if (begun.status() != 200 || !nonce.matches("[0-9a-f]{32}")) {
        throw new PublicationFailure(Kind.WEB_UNAVAILABLE, "no se pudo iniciar la recuperación de la época en la web");
      }
      WebActivationClient.Response completed = web.completeRecovery(control.epoch(), nonce);
      if (completed.status() != 200) {
        throw new PublicationFailure(Kind.WEB_UNAVAILABLE,
            "la web no aceptó la recuperación de la época (" + completed.result() + ")");
      }
      audit.record(c, PublicationAudit.WEB_EPOCH_RECOVERED, actor, Map.of("epoca", Long.toString(control.epoch())));
      reserved = web.reserve(control.epoch());
    }
    if (reserved.status() == 409) {
      throw new PublicationFailure(Kind.FENCED, "la web tiene una época más reciente que la nuestra");
    }
    if (reserved.status() != 200) {
      throw new PublicationFailure(Kind.WEB_UNAVAILABLE, "la web no reservó la época (" + reserved.status() + ")");
    }
  }

  /** Reserva temprana (sin garantías): cierra cuanto antes el paso a peticiones antiguas. */
  private void reserveEarly(PublicationControl control) {
    try {
      web.reserve(control.epoch());
    } catch (PublicationFailure e) {
      if (e.kind() == Kind.LOCK_LOST) {
        throw e;
      }
      // La web no responde o está bloqueada: se intentará (y recuperará) en P3.
    }
  }

  private void failGeneration(PublicationControl control, int version, Actor actor, String reason) {
    try {
      control.fenced("p1.fallo", c -> {
        if (PublicationRepository.toFailed(c, version, List.of(reason))) {
          audit.record(c, PublicationAudit.PUBLISH_FAILED, actor, Map.of("version", "v" + version, "motivo", reason));
        }
        return null;
      });
    } catch (RuntimeException ignored) {
      // Sin el control o sin PostgreSQL: la fila sigue «generando» y la reconciliación la cierra.
    }
  }

  // --- Reconciliar --------------------------------------------------------------------------------

  /** Reconciliación con el control (al arrancar la API o a petición). */
  public Report reconcile() {
    requireConfigured();
    try (PublicationControl control = take(Actor.RECONCILIATION)) {
      return reconcileWithin(control, SnapshotWriter.at(properties.snapshotsDir()));
    }
  }

  private Report reconcileWithin(PublicationControl control, SnapshotWriter writer) {
    ActivePointer.Read pointer = ActivePointer.read(properties.stateDir());
    Report report = control.fenced("reconciliacion", c -> reconciler.reconcile(c, pointer, writer.root()));
    // Con el control, ninguna carpeta temporal pertenece a una generación viva.
    writer.removeTemporaries();
    return report;
  }

  // --- Listado y purga ----------------------------------------------------------------------------

  public Overview overview() {
    if (!configured()) {
      return new Overview(false, null, null, null, null, null, List.of());
    }
    return read(c -> {
      PublicationRepository.Check check = PublicationRepository.check(c);
      return new Overview(true, check.status(), check.reason(), check.pointerVersion(), check.checkedAt(),
          PublicationRepository.epoch(c), PublicationRepository.latest(c, properties.listLimit()));
    });
  }

  /**
   * Purga (N4, M7): por defecto solo simula. Nunca la activa, la anterior (del puntero o de la base
   * de datos) ni las {@code keep} más recientes. Borra solo archivos: la fila se conserva.
   */
  public PurgeReport purge(boolean confirm, Actor actor) {
    requireConfigured();
    SnapshotWriter writer = SnapshotWriter.at(properties.snapshotsDir());
    try (PublicationControl control = take(actor)) {
      reconcileWithin(control, writer);
      ActivePointer.Read pointer = ActivePointer.read(properties.stateDir());
      List<Integer> eligible = read(c -> {
        List<Row> rows = PublicationRepository.all(c);
        Set<Integer> protectedVersions = new HashSet<>();
        if (pointer instanceof Valid valid) {
          protectedVersions.add(valid.version());
          if (valid.previous() != null) {
            protectedVersions.add(valid.previous());
          }
        }
        rows.stream().filter(r -> "activa".equals(r.state())).forEach(r -> {
          protectedVersions.add(r.version());
          if (r.previousVersion() != null) {
            protectedVersions.add(r.previousVersion());
          }
        });
        rows.stream().map(Row::version).sorted((a, b) -> b - a).limit(properties.keep()).forEach(protectedVersions::add);
        List<Integer> out = new ArrayList<>();
        for (Row r : rows) {
          if (r.filesPurgedAt() == null && !"activa".equals(r.state()) && !protectedVersions.contains(r.version())) {
            out.add(r.version());
          }
        }
        return out;
      });
      if (!confirm) {
        return new PurgeReport(false, eligible, List.of());
      }
      List<Integer> purged = new ArrayList<>();
      for (int version : eligible) {
        control.fenced("purga", c -> {
          if (PublicationRepository.markPurged(c, version)) {
            deleteVersion(writer.root(), version);
            audit.record(c, PublicationAudit.SNAPSHOT_PURGED, actor, Map.of("version", "v" + version));
            purged.add(version);
          }
          return null;
        });
      }
      return new PurgeReport(true, eligible, purged);
    }
  }

  /** Borra root/vN sin seguir enlaces (un enlace se borra a sí mismo, nunca su destino). */
  private static void deleteVersion(Path root, int version) throws IOException {
    Path dir = root.resolve("v" + version);
    if (!SnapshotFormat.isVersion(dir.getFileName().toString()) || !dir.getParent().equals(root)
        || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(dir, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
        if (e != null) {
          throw e;
        }
        Files.delete(d);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  // --- Utilidades ---------------------------------------------------------------------------------

  private PublicationControl take(Actor actor) {
    long floor = ActivePointer.epochFloor(ActivePointer.read(properties.stateDir()));
    return PublicationControl.take(dataSource, connections, properties, floor, actor.via(), clock, lockHooks,
        step -> hooks.at("dentro:" + step));
  }

  private Content readPublished() {
    try {
      return ContentExporter.readPublished(dataSource, null);
    } catch (ContentException e) {
      throw new PublicationFailure(Kind.DATABASE, "no se puede leer el estado publicado", e);
    }
  }

  private void hook(String point) {
    try {
      hooks.at(point);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PublicationFailure(Kind.LOCK_LOST, "interrumpido", e);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new PublicationFailure(Kind.INTERNAL, e.getMessage(), e);
    }
  }

  private void requireConfigured() {
    if (!configured()) {
      throw new PublicationFailure(Kind.NOT_CONFIGURED, "la publicación no está configurada");
    }
  }

  private static int folderMax(SnapshotWriter writer) {
    List<String> versions = writer.versions();
    return versions.isEmpty() ? 0 : (int) SnapshotFormat.number(versions.getLast());
  }

  private static List<String> issues(JsonNode body) {
    List<String> out = new ArrayList<>();
    for (JsonNode issue : body.path("incidencias").values()) {
      if (out.size() == 20) {
        out.add("…");
        break;
      }
      out.add(issue.asString(""));
    }
    return out;
  }

  @FunctionalInterface
  private interface SqlRead<T> {
    T run(Connection connection) throws SQLException;
  }

  /** Lectura sin cambios de estado (sin el control): una transacción de solo lectura. */
  private <T> T read(SqlRead<T> work) {
    try (Connection c = dataSource.getConnection()) {
      c.setReadOnly(true);
      return work.run(c);
    } catch (SQLException e) {
      throw new PublicationFailure(Kind.DATABASE, "error de PostgreSQL", e);
    }
  }
}
