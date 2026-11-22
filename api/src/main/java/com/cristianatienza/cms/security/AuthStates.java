package com.cristianatienza.cms.security;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;

/**
 * Factores y estados de autenticación (diseño aprobado de la fase 6.2):
 *
 * <pre>
 *   A   anónimo
 *   P1  FACTOR_PASSWORD + STATE_ENROLL                     primer acceso: solo registrar una passkey
 *   P2  FACTOR_PASSWORD                                    falta autenticarse con la passkey
 *   R   FACTOR_PASSWORD + FACTOR_RECOVERY + STATE_ENROLL   recuperación: solo registrar una passkey
 *   F   FACTOR_PASSWORD + FACTOR_WEBAUTHN + ROLE_&lt;rol&gt;     acceso completo
 * </pre>
 *
 * Las transiciones están en api/docs/seguridad.md; cada una rota el identificador de sesión.
 */
public final class AuthStates {

  public static final String FACTOR_PASSWORD = FactorGrantedAuthority.PASSWORD_AUTHORITY;
  public static final String FACTOR_WEBAUTHN = FactorGrantedAuthority.WEBAUTHN_AUTHORITY;
  public static final String STATE_ENROLL = "STATE_ENROLL";
  /** Código de recuperación canjeado (estado R). Nunca da FACTOR_WEBAUTHN. */
  public static final String FACTOR_RECOVERY = "FACTOR_RECOVERY";

  /** Momento de la autenticación con contraseña (caducidad absoluta de la sesión). */
  public static final String AUTHENTICATED_AT = "cms.auth.authenticatedAt";
  /** Última verificación con passkey (acceso a F o step-up): ventana de la verificación reciente. */
  public static final String WEBAUTHN_VERIFIED_AT = "cms.auth.webauthnVerifiedAt";
  /**
   * Sello de seguridad (H7, 6.2.6): el password_changed_at del usuario leído al iniciar sesión. La
   * recuperación de emergencia lo cambia y deja inválidas todas las sesiones anteriores.
   */
  public static final String SECURITY_STAMP = "cms.auth.securityStamp";

  private AuthStates() {}

  /** Estado que la API comunica al cliente (sin datos personales). */
  public enum State {
    PASSKEY_ENROLLMENT_REQUIRED,
    PASSKEY_REQUIRED,
    RECOVERY,
    AUTHENTICATED
  }

  public static State of(Authentication authentication) {
    Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
    boolean password = has(authorities, FACTOR_PASSWORD);
    boolean webauthn = has(authorities, FACTOR_WEBAUTHN);
    if (password && webauthn) {
      return State.AUTHENTICATED;
    }
    if (has(authorities, FACTOR_RECOVERY)) {
      return State.RECOVERY;
    }
    return has(authorities, STATE_ENROLL) ? State.PASSKEY_ENROLLMENT_REQUIRED : State.PASSKEY_REQUIRED;
  }

  static boolean has(Collection<? extends GrantedAuthority> authorities, String name) {
    return authorities.stream().anyMatch(a -> name.equals(a.getAuthority()));
  }

  /**
   * Principal guardado en la sesión: lo mínimo. Su nombre es el id (no el correo), que es lo que
   * Spring Session indexa para cerrar todas las sesiones de un usuario.
   */
  public record SessionUser(UUID id, String role) implements Principal, Serializable {
    @Serial private static final long serialVersionUID = 1L;

    @Override
    public String getName() {
      return id.toString();
    }
  }
}
