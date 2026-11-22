package com.cristianatienza.cms.security;

import com.cristianatienza.cms.security.policy.CmsAdmin;
import com.cristianatienza.cms.security.policy.CmsEditor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

/** Comprobador de la política de cierre por defecto (G3), usado por los tests de arquitectura. */
final class EndpointPolicy {

  private EndpointPolicy() {}

  /**
   * Endpoints sin política declarada: fuera de /api/auth (matriz de EndpointProtectionTests), de
   * /actuator y de /error, todo método debe estar bajo /api/cms y llevar @CmsEditor o @CmsAdmin
   * (en el método o en su clase).
   */
  static List<String> violations(Map<RequestMappingInfo, HandlerMethod> handlers) {
    TreeSet<String> out = new TreeSet<>();
    handlers.forEach((info, handler) -> {
      for (String path : info.getPatternValues()) {
        if (path.startsWith("/api/auth/") || path.startsWith("/actuator") || path.equals("/error")) {
          continue;
        }
        Method method = handler.getMethod();
        boolean declared = AnnotatedElementUtils.hasAnnotation(method, CmsEditor.class)
            || AnnotatedElementUtils.hasAnnotation(method, CmsAdmin.class)
            || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), CmsEditor.class)
            || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), CmsAdmin.class);
        if (!path.startsWith("/api/cms/") || !declared) {
          out.add(info.getMethodsCondition().getMethods() + " " + path);
        }
      }
    });
    return new ArrayList<>(out);
  }
}
