package com.cristianatienza.cms.content.publication;

import com.cristianatienza.cms.content.publication.PublicationFailure.Kind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * El control de la publicación (fase 6.3.4): el bloqueo asesor más una ÉPOCA nueva de PostgreSQL.
 *
 * <p><b>Fencing.</b> Toda transacción que cambia el estado ({@link #fenced}) empieza con
 * {@code SELECT epoch … FOR UPDATE} sobre la fila de la época y aborta si ya no es la suya. Tomar el
 * control incrementa esa misma fila. El bloqueo de la fila ordena las dos operaciones: o la
 * transacción del publicador antiguo bloquea la fila primero (y su escritura queda confirmada antes
 * de que el nuevo obtenga su época), o el nuevo incrementa primero (y el antiguo lee otra época y
 * no escribe nada). No hay intervalo entre comprobar y escribir.
 */
final class PublicationControl implements AutoCloseable {

  /** Trabajo dentro de una transacción protegida (la conexión no es autocommit). */
  @FunctionalInterface
  interface FencedWork<T> {
    T run(Connection connection) throws Exception;
  }

  /** Punto de prueba al entrar en la sección protegida (ya comprobada la época). */
  @FunctionalInterface
  interface FenceHook {
    void inside(String step) throws Exception;
  }

  private final DataSource dataSource;
  private final PublicationLock lock;
  private final long epoch;
  private final FenceHook hook;

  private PublicationControl(DataSource dataSource, PublicationLock lock, long epoch, FenceHook hook) {
    this.dataSource = dataSource;
    this.lock = lock;
    this.epoch = epoch;
    this.hook = hook;
  }

  /**
   * Toma el control: el bloqueo (dentro del plazo) y una época mayor que cualquier anterior y que
   * {@code floor} (la de activa.json: cubre una restauración de PostgreSQL).
   */
  static PublicationControl take(DataSource dataSource, PublicationLock.ConnectionFactory factory,
      PublicationProperties properties, long floor, String holder, LongSupplier clock,
      PublicationLock.Hooks lockHooks, FenceHook hook) {
    long deadline = clock.getAsLong() + properties.lockWait().toNanos();
    PublicationLock lock = PublicationLock.acquire(factory, properties, deadline, clock, lockHooks);
    try {
      long epoch = newEpoch(dataSource, floor, holder, deadline, clock);
      return new PublicationControl(dataSource, lock, epoch, hook);
    } catch (RuntimeException e) {
      lock.close();
      throw e;
    }
  }

  private static long newEpoch(DataSource dataSource, long floor, String holder, long deadline, LongSupplier clock) {
    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - clock.getAsLong());
    if (remaining <= 0) {
      throw PublicationLock.busy();
    }
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        try (Statement s = c.createStatement()) {
          // La fila puede estar bloqueada por la transacción protegida de un publicador anterior:
          // se espera, como mucho, lo que quede del plazo.
          s.execute("SET LOCAL lock_timeout = " + Math.max(1, remaining));
        }
        long epoch;
        try (PreparedStatement ps = c.prepareStatement("UPDATE content_publication_lease"
            + " SET epoch = greatest(epoch, ?) + 1, holder = ?, acquired_at = clock_timestamp()"
            + " WHERE id = 1 RETURNING epoch")) {
          ps.setLong(1, floor);
          ps.setString(2, holder.length() > 200 ? holder.substring(0, 200) : holder);
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            epoch = rs.getLong(1);
          }
        }
        c.commit();
        return epoch;
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      if ("55P03".equals(e.getSQLState())) {
        throw PublicationLock.busy();
      }
      throw new PublicationFailure(Kind.DATABASE, "no se puede tomar el control de la publicación", e);
    }
  }

  long epoch() {
    return epoch;
  }

  int lockPid() {
    return lock.pid();
  }

  /** Detección temprana (no es la garantía: la garantía es {@link #fenced}). */
  void ensureHeld() {
    lock.ensureHeld();
  }

  /**
   * Ejecuta {@code work} en una transacción protegida: la época se comprueba con la fila bloqueada
   * hasta el COMMIT. Si ya no es la nuestra, nada se escribe.
   */
  <T> T fenced(String step, FencedWork<T> work) {
    lock.ensureHeld();
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        try (Statement s = c.createStatement()) {
          s.execute("SET LOCAL lock_timeout = 5000");
        }
        long current;
        try (Statement s = c.createStatement();
            ResultSet rs = s.executeQuery("SELECT epoch FROM content_publication_lease WHERE id = 1 FOR UPDATE")) {
          rs.next();
          current = rs.getLong(1);
        }
        if (current != epoch) {
          c.rollback();
          throw new PublicationFailure(Kind.FENCED,
              "otro publicador ha tomado el control (época " + current + ", la nuestra " + epoch + ")");
        }
        // Y la sesión del bloqueo sigue viva (desde ESTA conexión: la del bloqueo es del heartbeat).
        // Detecta una pérdida al momento, sin esperar al siguiente latido.
        try (Statement s = c.createStatement();
            ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = "
                + lock.pid() + " AND classid = " + PublicationLock.KEY_CLASS + " AND objid = "
                + PublicationLock.KEY_OBJECT + " AND objsubid = 2 AND granted")) {
          rs.next();
          if (rs.getInt(1) != 1) {
            c.rollback();
            lock.lostDetected(new IllegalStateException("la sesión del bloqueo ya no lo tiene"));
            throw new PublicationFailure(Kind.LOCK_LOST, "se perdió el bloqueo de publicación");
          }
        }
        hook.inside(step);
        T result = work.run(c);
        c.commit();
        return result;
      } catch (Exception e) {
        rollbackQuietly(c);
        throw e;
      }
    } catch (PublicationFailure e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PublicationFailure(Kind.LOCK_LOST, "interrumpido", e);
    } catch (SQLException e) {
      throw new PublicationFailure(Kind.DATABASE, "error de PostgreSQL en la publicación", e);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new PublicationFailure(Kind.INTERNAL, "error en la publicación", e);
    }
  }

  private static void rollbackQuietly(Connection c) {
    try {
      c.rollback();
    } catch (SQLException ignored) {
      // La conexión se cierra a continuación: la transacción no se confirma.
    }
  }

  @Override
  public void close() {
    lock.close();
  }
}
