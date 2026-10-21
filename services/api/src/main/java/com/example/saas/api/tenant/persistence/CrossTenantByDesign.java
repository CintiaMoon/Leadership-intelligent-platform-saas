package com.example.saas.api.tenant.persistence;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a repository method that deliberately reaches across companies. A test fails the build for any other query
 * method on a tenant-owned repository that does not take a {@code companyId} (D-110).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CrossTenantByDesign {

	String reason();

}
