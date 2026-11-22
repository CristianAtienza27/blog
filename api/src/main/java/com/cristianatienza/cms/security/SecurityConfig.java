package com.cristianatienza.cms.security;

import static com.cristianatienza.cms.security.AuthStates.FACTOR_PASSWORD;
import static com.cristianatienza.cms.security.AuthStates.FACTOR_RECOVERY;
import static com.cristianatienza.cms.security.AuthStates.FACTOR_WEBAUTHN;
import static com.cristianatienza.cms.security.AuthStates.STATE_ENROLL;
import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority;
import static org.springframework.security.authorization.AuthorizationManagers.allOf;
import static org.springframework.security.authorization.AuthorizationManagers.anyOf;
import static org.springframework.security.authorization.AuthorizationManagers.not;

import com.cristianatienza.cms.security.audit.CsrfRejections;
import com.cristianatienza.cms.web.ApiProblems;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Seguridad de la API (fases 6.2.2 y 6.2.3). Todo está cerrado por defecto; solo es público lo
 * necesario para iniciar sesión y el estado de salud. Mismo origen (diseño A): sin CORS.
 *
 * <p>Matriz de estados (api/docs/seguridad.md): cada endpoint declara exactamente qué estados lo
 * pueden llamar. No hay filtros WebAuthn de Spring (D1): todas las rutas pasan por estas reglas.
 *
 * <p>IMPORTANTE: hasta completar la protección contra fuerza bruta (fase 6.2.4), esta API de
 * autenticación NO puede exponerse a Internet. Argon2id y su semáforo acotan el coste por
 * petición, pero no sustituyen a la limitación de intentos.
 */
@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

  public static final String SESSION_COOKIE = "__Host-SESSION";
  public static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";
  public static final String CSRF_HEADER = "X-XSRF-TOKEN";

  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http, CookieCsrfTokenRepository csrfTokens,
      SecurityContextRepository contexts, Clock clock, CsrfRejections csrfRejections, SecurityEvents events,
      AppUserRepository users,
      @Value("${cms.security.session.absolute-timeout:12h}") Duration absoluteTimeout,
      @Value("${cms.security.webauthn.step-up-window:5m}") Duration stepUpWindow) throws Exception {
    // No es un bean: Spring Boot lo registraría además como filtro global y se ejecutaría dos veces.
    AbsoluteSessionTimeoutFilter absoluteTimeoutFilter = new AbsoluteSessionTimeoutFilter(clock, absoluteTimeout, events);
    // Sello de seguridad en cada petición con sesión (H7, 6.2.6).
    SecurityStampFilter stampFilter = new SecurityStampFilter(users, events);
    // Estados (AuthStates): P1 o R (registrar la passkey), P2 (falta la passkey) y F (completo).
    AuthorizationManager<RequestAuthorizationContext> password = hasAuthority(FACTOR_PASSWORD);
    AuthorizationManager<RequestAuthorizationContext> enrolling = allOf(password, hasAuthority(STATE_ENROLL),
        not(hasAuthority(FACTOR_WEBAUTHN)));
    AuthorizationManager<RequestAuthorizationContext> p2 = allOf(password, not(hasAuthority(STATE_ENROLL)),
        not(hasAuthority(FACTOR_RECOVERY)), not(hasAuthority(FACTOR_WEBAUTHN)));
    AuthorizationManager<RequestAuthorizationContext> full = allOf(password, hasAuthority(FACTOR_WEBAUTHN),
        not(hasAuthority(STATE_ENROLL)), not(hasAuthority(FACTOR_RECOVERY)));
    AuthorizationManager<RequestAuthorizationContext> fullRecent = allOf(full, recentPasskey(clock, stepUpWindow));
    http
        // CSRF para una aplicación de una sola página: token en una cookie legible por el CMS
        // (mismo origen) que vuelve en la cabecera X-XSRF-TOKEN.
        .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
        // Las cabeceras de seguridad las pone ApiResponseFilter, antes que Spring Security y en
        // todos los despachos (6.2.5); las de Spring Security se desactivan para no duplicarlas.
        .headers(headers -> headers.disable())
        .securityContext(sc -> sc.securityContextRepository(contexts))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .requestCache(rc -> rc.requestCache(new NullRequestCache()))
        .formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        // El cierre de sesión lo hace AuthController: así también exige sesión (matriz de estados).
        .logout(l -> l.disable())
        .exceptionHandling(e -> e
            .authenticationEntryPoint((request, response, ex) ->
                ApiProblems.write(request, response, 401, "unauthenticated", "no autenticado"))
            .accessDeniedHandler((request, response, ex) -> {
              // Rechazos CSRF agregados por IP y minuto (E5): O(1), nunca bloquea ni falla.
              if (ex instanceof CsrfException) {
                csrfRejections.record(request.getRemoteAddr());
              }
              if (ex instanceof CsrfException) {
                ApiProblems.write(request, response, 403, "csrf", "token CSRF ausente o no válido");
              } else {
                ApiProblems.write(request, response, 403, "forbidden", "acceso denegado");
              }
            }))
        .addFilterAfter(absoluteTimeoutFilter, SecurityContextHolderFilter.class)
        .addFilterAfter(stampFilter, AbsoluteSessionTimeoutFilter.class)
        .authorizeHttpRequests(auth -> auth
            // Solo el despacho interno de errores (p. ej. 415 o 400 antes de llegar al controlador):
            // muestra el error ya producido, sin trazas ni mensajes (valores por defecto de Spring Boot).
            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
            .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
            .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
            // Sondas (6.2.7.2, D1): solo el estado, sin detalles. Liveness para Docker y Kubernetes
            // (la JVM está viva); readiness para quien reparte el tráfico (incluye PostgreSQL).
            .requestMatchers(HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
            // Con sesión (P1, P2, R, F): consultar el estado y cerrar sesión.
            .requestMatchers(HttpMethod.GET, "/api/auth/me").access(password)
            .requestMatchers(HttpMethod.POST, "/api/auth/logout").access(password)
            // Registrar una passkey: P1 o R (su única salida), o F con verificación reciente.
            .requestMatchers(HttpMethod.POST, "/api/auth/passkeys/registration/options", "/api/auth/passkeys/registration")
                .access(anyOf(enrolling, fullRecent))
            // Segundo factor: solo P2 (passkey o código de recuperación).
            .requestMatchers(HttpMethod.POST, "/api/auth/passkeys/authentication/options", "/api/auth/passkeys/authentication",
                "/api/auth/recovery").access(p2)
            // F: verificación reciente, gestión de passkeys y cierre de todas las sesiones.
            .requestMatchers(HttpMethod.POST, "/api/auth/step-up/options", "/api/auth/step-up").access(full)
            .requestMatchers(HttpMethod.GET, "/api/auth/passkeys").access(full)
            // Dispositivos de confianza (6.2.4): ver y revocar, en F sin verificación reciente
            // (revocar es una acción protectora).
            .requestMatchers(HttpMethod.GET, "/api/auth/devices").access(full)
            .requestMatchers(HttpMethod.DELETE, "/api/auth/devices/{id}").access(full)
            .requestMatchers(HttpMethod.POST, "/api/auth/sessions/revoke-all").access(full)
            // F con verificación reciente: acciones que podrían facilitar apoderarse de la cuenta.
            .requestMatchers(HttpMethod.DELETE, "/api/auth/passkeys/{id}").access(fullRecent)
            .requestMatchers(HttpMethod.POST, "/api/auth/recovery-codes").access(fullRecent)
            // API de contenido del CMS (6.3 en adelante, decisión G3): F con rol a nivel de URL y,
            // además, una anotación de política obligatoria en cada método (@CmsEditor o
            // @CmsAdmin, comprobado por EndpointPolicyArchitectureTests).
            .requestMatchers("/api/cms/**").access(allOf(full, anyOf(hasAuthority("ROLE_ADMIN"), hasAuthority("ROLE_EDITOR"))))
            // Todo lo demás, cerrado.
            .anyRequest().denyAll());
    return http.build();
  }

  /** Verificación con passkey (acceso a F o step-up) dentro de la ventana, con el reloj de la API. */
  static AuthorizationManager<RequestAuthorizationContext> recentPasskey(Clock clock, Duration window) {
    return (authentication, context) -> {
      var session = context.getRequest().getSession(false);
      Object verifiedAt = session == null ? null : session.getAttribute(AuthStates.WEBAUTHN_VERIFIED_AT);
      boolean recent = verifiedAt instanceof Instant instant && !instant.plus(window).isBefore(clock.instant());
      return new AuthorizationDecision(recent);
    };
  }

  /** Cookie __Host-XSRF-TOKEN: Secure, Path=/, sin Domain, SameSite=Strict, legible por el CMS. */
  @Bean
  CookieCsrfTokenRepository csrfTokenRepository() {
    CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    repository.setCookieName(CSRF_COOKIE);
    repository.setHeaderName(CSRF_HEADER);
    repository.setCookiePath("/");
    repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict"));
    return repository;
  }

  /** Cookie de sesión __Host-SESSION: Secure, HttpOnly, Path=/, sin Domain, SameSite=Strict. */
  @Bean
  CookieSerializer cookieSerializer() {
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName(SESSION_COOKIE);
    serializer.setUseSecureCookie(true);
    serializer.setUseHttpOnlyCookie(true);
    serializer.setSameSite("Strict");
    serializer.setCookiePath("/");
    return serializer;
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

}
