package com.cristianatienza.cms.security.policy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exige además una verificación con passkey reciente (step-up aprobado en la 6.2.3, ventana
 * cms.security.webauthn.step-up-window). Se combina con {@link CmsEditor} o {@link CmsAdmin}: la
 * aplica un interceptor propio (Spring solo admite un @PreAuthorize por método). Pensada para las
 * acciones destructivas del CMS que se decidan en sus fases.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RecentPasskey {}
