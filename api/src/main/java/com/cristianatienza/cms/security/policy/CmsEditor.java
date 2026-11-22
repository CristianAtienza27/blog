package com.cristianatienza.cms.security.policy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Política de un endpoint de contenido (/api/cms/**, decisión G3): acceso completo (F, contraseña y
 * passkey del mismo usuario) con rol ADMIN o EDITOR. Todo método bajo /api/cms debe declarar
 * {@code @CmsEditor} o {@link CmsAdmin} (EndpointPolicyArchitectureTests).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('FACTOR_PASSWORD') and hasAuthority('FACTOR_WEBAUTHN')"
    + " and !hasAuthority('STATE_ENROLL') and !hasAuthority('FACTOR_RECOVERY') and hasAnyRole('ADMIN', 'EDITOR')")
public @interface CmsEditor {}
