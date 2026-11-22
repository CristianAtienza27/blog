package cmstest;

import com.cristianatienza.cms.security.policy.CmsAdmin;
import com.cristianatienza.cms.security.policy.CmsEditor;
import com.cristianatienza.cms.security.policy.RecentPasskey;
import com.cristianatienza.cms.web.ApiResponseFilter;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controladores SOLO de pruebas (nunca en producción) bajo /api/cms, para comprobar la política de
 * autorización de la 6.2.5 antes de que existan endpoints de contenido (6.3), y el comprobador de
 * EndpointPolicyArchitectureTests. Fuera del paquete de la aplicación: el escaneo de componentes no
 * los encuentra; solo existen donde un test los importa.
 */
public final class CmsTestControllers {

  private CmsTestControllers() {}

  @RestController
  @RequestMapping("/api/cms/test")
  public static class Annotated {

    @CmsEditor
    @GetMapping("/editor")
    Map<String, String> editor() {
      return Map.of("ok", "editor");
    }

    @CmsAdmin
    @PostMapping("/admin")
    Map<String, String> admin() {
      return Map.of("ok", "admin");
    }

    @CmsAdmin
    @RecentPasskey
    @PostMapping("/admin-recent")
    Map<String, String> adminRecent() {
      return Map.of("ok", "admin-recent");
    }

    /** El identificador de correlación que ve el código (MDC), para comprobar que no se mezcla. */
    @CmsEditor
    @GetMapping("/mdc")
    Map<String, String> mdc() {
      return Map.of("mdc", String.valueOf(MDC.get(ApiResponseFilter.MDC_KEY)));
    }

    /** Un error inesperado con un mensaje «secreto»: la respuesta no debe mostrar nada de él. */
    @CmsEditor
    @GetMapping("/boom")
    Map<String, String> boom() {
      LoggerFactory.getLogger(Annotated.class).info("antes del fallo provocado");
      throw new IllegalStateException("detalle-interno-secreto en com.cristianatienza.Secreto");
    }
  }

  /** Sin anotación de política: el comprobador debe detectarlo (nunca se registra en producción). */
  @RestController
  @RequestMapping("/api/cms/test-unannotated")
  public static class Unannotated {

    @GetMapping
    Map<String, String> forgotten() {
      return Map.of("ok", "olvidado");
    }
  }
}
