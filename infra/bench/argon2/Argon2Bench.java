import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Coste de un Argon2id con los parámetros de la API (6.2.7, prueba A): la misma clase y los mismos
 * valores que PasswordHashing (m = 19 MiB, t = 2, p = 1, sal de 16 bytes, hash de 32), con las
 * bibliotecas de la imagen de la API. Mide la verificación (lo que hace cada inicio de sesión) tras
 * un calentamiento, con 1 hilo y con tantos hilos simultáneos como se indique.
 *
 * Uso: java -cp '/applib/*' Argon2Bench.java <repeticiones> <hilos,...>
 * Salida: una línea JSON por número de hilos. La contraseña es aleatoria y no se imprime.
 */
public class Argon2Bench {

  public static void main(String[] args) throws Exception {
    int repetitions = Integer.parseInt(args[0]);
    int[] threads = Arrays.stream(args[1].split(",")).mapToInt(Integer::parseInt).toArray();
    Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    String password = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
    String hash = encoder.encode(password);
    for (int i = 0; i < 10; i++) {
      encoder.matches(password, hash); // calentamiento (JIT)
    }
    Runtime rt = Runtime.getRuntime();
    for (int n : threads) {
      ExecutorService pool = Executors.newFixedThreadPool(n);
      List<Double> times = new ArrayList<>();
      CountDownLatch start = new CountDownLatch(1);
      List<Future<List<Double>>> futures = new ArrayList<>();
      long wall = System.nanoTime();
      for (int t = 0; t < n; t++) {
        futures.add(pool.submit(() -> {
          start.await();
          List<Double> mine = new ArrayList<>();
          for (int i = 0; i < repetitions; i++) {
            long s = System.nanoTime();
            if (!encoder.matches(password, hash)) {
              throw new IllegalStateException("verificación fallida");
            }
            mine.add((System.nanoTime() - s) / 1e6);
          }
          return mine;
        }));
      }
      start.countDown();
      for (Future<List<Double>> f : futures) {
        times.addAll(f.get());
      }
      double seconds = (System.nanoTime() - wall) / 1e9;
      pool.shutdown();
      times.sort(Double::compare);
      System.out.printf(java.util.Locale.ROOT,
          "{\"hilos\":%d,\"verificaciones\":%d,\"porSegundo\":%.1f,\"p50\":%.1f,\"p95\":%.1f,\"max\":%.1f,"
              + "\"min\":%.1f,\"cpusJvm\":%d,\"heapMaxMiB\":%d}%n",
          n, times.size(), times.size() / seconds, pct(times, 0.5), pct(times, 0.95), times.getLast(),
          times.getFirst(), rt.availableProcessors(), rt.maxMemory() / 1048576);
    }
  }

  private static double pct(List<Double> sorted, double q) {
    return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(q * sorted.size()) - 1));
  }
}
