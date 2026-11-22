package cmstest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SOLO de pruebas: un filtro anterior a Spring Security que responde con sendError(503) en una
 * ruta concreta, para recorrer el despacho de errores del contenedor (/error) y comprobar que
 * también ahí salen las cabeceras y el ProblemDetail.
 */
@Configuration
public class SendErrorFilterConfig {

  public static final String PATH = "/api/cms/test/send-error";

  @Bean
  FilterRegistrationBean<OncePerRequestFilter> sendErrorFilter() {
    OncePerRequestFilter filter = new OncePerRequestFilter() {
      @Override
      protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
      }

      @Override
      protected void doFilterInternal(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
          jakarta.servlet.FilterChain chain) throws java.io.IOException {
        response.sendError(503);
      }
    };
    FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setOrder(-200); // antes que Spring Security (-100) y después de ApiResponseFilter
    return registration;
  }
}
