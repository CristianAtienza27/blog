package com.cristianatienza.cms.security.policy;

import com.cristianatienza.cms.security.AuthStates;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Interceptor de {@link RecentPasskey}: verificación con passkey dentro de la ventana, con el reloj de la API. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PolicyConfig {

  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static Advisor recentPasskeyAdvisor(Clock clock, @Value("${cms.security.webauthn.step-up-window:5m}") Duration window) {
    AuthorizationManager<MethodInvocation> recent = (authentication, invocation) -> {
      HttpSession session = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
          ? attributes.getRequest().getSession(false) : null;
      Object verifiedAt = session == null ? null : session.getAttribute(AuthStates.WEBAUTHN_VERIFIED_AT);
      return new AuthorizationDecision(verifiedAt instanceof Instant instant
          && !instant.plus(window).isBefore(clock.instant()));
    };
    AuthorizationManagerBeforeMethodInterceptor interceptor = new AuthorizationManagerBeforeMethodInterceptor(
        new AnnotationMatchingPointcut(null, RecentPasskey.class, true), recent);
    // Después de @PreAuthorize (el rol y los factores se comprueban primero).
    interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() + 1);
    return interceptor;
  }
}
