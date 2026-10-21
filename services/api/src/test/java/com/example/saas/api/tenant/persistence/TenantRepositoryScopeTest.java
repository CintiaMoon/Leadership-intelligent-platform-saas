package com.example.saas.api.tenant.persistence;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build when a repository of a tenant-owned entity (one with a {@code companyId} field) has a method that
 * is not scoped by company (D-110). Such a repository must not extend {@code JpaRepository} or {@code CrudRepository},
 * whose inherited {@code findById} and {@code findAll} cannot be scoped.
 */
class TenantRepositoryScopeTest {

	@Test
	void everyTenantOwnedRepositoryMethodIsScopedByCompany() throws Exception {
		List<String> violations = new ArrayList<>();
		int checked = 0;
		for (Class<?> repository : repositories()) {
			Class<?> entity = entityOf(repository);
			if (!hasCompanyId(entity)) {
				continue;
			}
			checked++;
			if (org.springframework.data.repository.CrudRepository.class.isAssignableFrom(repository)) {
				violations.add(repository.getSimpleName() + " extends a Crud/Jpa repository with unscoped methods");
			}
			for (Method method : repository.getDeclaredMethods()) {
				if (!isScoped(method)) {
					violations.add(repository.getSimpleName() + "." + method.getName());
				}
			}
		}
		assertThat(checked).as("tenant-owned repositories found").isPositive();
		assertThat(violations).as("methods that do not take a companyId").isEmpty();
	}

	@Test
	void theCheckRejectsAnUnscopedMethod() throws Exception {
		assertThat(isScoped(Sample.class.getMethod("findByName", String.class))).isFalse();
		assertThat(isScoped(Sample.class.getMethod("findByCompanyIdAndName", java.util.UUID.class, String.class)))
			.isTrue();
		assertThat(isScoped(Sample.class.getMethod("anyMethod", String.class))).isFalse();
	}

	@SuppressWarnings("unused")
	private interface Sample {

		Object findByName(String name);

		Object findByCompanyIdAndName(java.util.UUID companyId, String name);

		Object anyMethod(@Param("name") String name);

	}

	private static boolean isScoped(Method method) {
		if (method.isAnnotationPresent(CrossTenantByDesign.class) || Modifier.isStatic(method.getModifiers())) {
			return true;
		}
		if (method.getName().equals("save") || method.getName().equals("saveAll")) {
			return true;
		}
		boolean derivedByCompany = method.getName().contains("CompanyId");
		boolean namedParameter = Arrays.stream(method.getParameters()).anyMatch(p -> p.getName().equals("companyId")
				|| (p.isAnnotationPresent(Param.class) && p.getAnnotation(Param.class).value().equals("companyId")));
		return derivedByCompany || namedParameter;
	}

	private static List<Class<?>> repositories() throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(
					org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
				return definition.getMetadata().isInterface();
			}
		};
		scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
		List<Class<?>> found = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents("com.example.saas.api")) {
			found.add(Class.forName(definition.getBeanClassName()));
		}
		return found;
	}

	private static Class<?> entityOf(Class<?> repository) {
		for (java.lang.reflect.Type type : repository.getGenericInterfaces()) {
			if (type instanceof java.lang.reflect.ParameterizedType parameterized
					&& parameterized.getActualTypeArguments()[0] instanceof Class<?> entity) {
				return entity;
			}
		}
		return Object.class;
	}

	private static boolean hasCompanyId(Class<?> entity) {
		return Arrays.stream(entity.getDeclaredFields()).anyMatch(f -> f.getName().equals("companyId"));
	}

}
