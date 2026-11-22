package com.cristianatienza.cms.security.devices;

import com.cristianatienza.cms.security.ApiErrors.AuthFailure;
import com.cristianatienza.cms.security.AttemptGuard;
import com.cristianatienza.cms.security.AuthSessions;
import com.cristianatienza.cms.security.devices.TrustedDevices.DeviceView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dispositivos de confianza del usuario (F): verlos y revocarlos. Revocar es una acción protectora
 * (sin verificación reciente) y solo quita el alivio de los límites, nunca el acceso.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/auth/devices")
public class DeviceController {

  private final TrustedDevices devices;

  DeviceController(TrustedDevices devices) {
    this.devices = devices;
  }

  @GetMapping
  List<DeviceView> list(Authentication authentication, HttpServletRequest request) {
    return devices.list(AuthSessions.user(authentication).id(),
        AttemptGuard.trustOf(request.getSession(false)).familyId());
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> revoke(Authentication authentication, @PathVariable("id") UUID id) {
    if (!devices.revokeFamily(AuthSessions.user(authentication).id(), id, "user")) {
      throw new AuthFailure(HttpStatus.NOT_FOUND, "dispositivo no encontrado");
    }
    return ResponseEntity.noContent().build();
  }
}
