package org.springframework.boot.context.properties;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Minimal stand-in for Spring Boot's real annotation. This fixture project has no build file and
 * no real dependency on spring-boot-context — only the fully qualified name matters to
 * ConfigurationPropertiesContractProvider, which resolves this class by name rather than assuming
 * the real library is on the classpath.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigurationProperties {
    String value() default "";
    String prefix() default "";
    boolean ignoreUnknownFields() default true;
}
