package com.example.saas.api.tenant.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.example.saas.api.tenant.domain.Permission;

/**
 * Marks an {@code AuthorizedCompany} controller parameter and names the permission the endpoint needs. The company
 * comes from the {@code companyId} path variable; the user from the session.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Authorized {

	Permission value();

}
