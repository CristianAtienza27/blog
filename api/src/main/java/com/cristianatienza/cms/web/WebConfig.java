package com.cristianatienza.cms.web;

import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/** Respuestas de la API (6.2.5): primer filtro y rechazos del cortafuegos de Spring Security. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebConfig {

  /** Antes que cualquier otro filtro (también Spring Security), en todos los despachos. */
  @Bean
  FilterRegistrationBean<ApiResponseFilter> apiResponseFilter() {
    FilterRegistrationBean<ApiResponseFilter> registration = new FilterRegistrationBean<>(new ApiResponseFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC,
        DispatcherType.FORWARD, DispatcherType.INCLUDE));
    registration.addUrlPatterns("/*");
    return registration;
  }

  /** Peticiones rechazadas por el cortafuegos (rutas anómalas): 400 ProblemDetail, sin detalles. */
  @Bean
  RequestRejectedHandler requestRejectedHandler() {
    return (request, response, ex) -> ApiProblems.write(request, response, 400, "bad_request", "solicitud no válida");
  }
}
