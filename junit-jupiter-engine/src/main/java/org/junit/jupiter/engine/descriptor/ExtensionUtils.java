/*
 * Copyright 2015-2021 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.jupiter.engine.descriptor;

import static java.util.stream.Collectors.toList;
import static org.junit.platform.commons.util.AnnotationUtils.findAnnotation;
import static org.junit.platform.commons.util.AnnotationUtils.findRepeatableAnnotations;
import static org.junit.platform.commons.util.AnnotationUtils.isAnnotated;
import static org.junit.platform.commons.util.ReflectionUtils.HierarchyTraversalMode.TOP_DOWN;
import static org.junit.platform.commons.util.ReflectionUtils.findFields;
import static org.junit.platform.commons.util.ReflectionUtils.getDeclaredConstructor;
import static org.junit.platform.commons.util.ReflectionUtils.isNotPrivate;
import static org.junit.platform.commons.util.ReflectionUtils.tryToReadFieldValue;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.engine.extension.ExtensionRegistrar;
import org.junit.jupiter.engine.extension.MutableExtensionRegistry;
import org.junit.platform.commons.util.Preconditions;
import org.junit.platform.commons.util.ReflectionUtils;

/**
 * Collection of utilities for working with extensions and the extension registry.
 *
 * @since 5.1
 * @see ExtensionRegistrar
 * @see MutableExtensionRegistry
 * @see ExtendWith
 * @see RegisterExtension
 */
final class ExtensionUtils {

	private ExtensionUtils() {
		/* no-op */
	}

	/**
	 * Populate a new {@link MutableExtensionRegistry} from extension types declared via
	 * {@link ExtendWith @ExtendWith} on the supplied {@link AnnotatedElement}.
	 *
	 * @param parentRegistry the parent extension registry to set in the newly
	 * created registry; never {@code null}
	 * @param annotatedElement the annotated element on which to search for
	 * declarations of {@code @ExtendWith}; never {@code null}
	 *
	 * @return the new extension registry; never {@code null}
	 * @since 5.0
	 */
	static MutableExtensionRegistry populateNewExtensionRegistryFromExtendWithAnnotation(
			MutableExtensionRegistry parentRegistry, AnnotatedElement annotatedElement) {

		Preconditions.notNull(parentRegistry, "Parent ExtensionRegistry must not be null");
		Preconditions.notNull(annotatedElement, "AnnotatedElement must not be null");

		// @formatter:off
		List<Class<? extends Extension>> extensionTypes = findRepeatableAnnotations(annotatedElement, ExtendWith.class).stream()
				.map(ExtendWith::value)
				.flatMap(Arrays::stream)
				.collect(toList());
		// @formatter:on

		return MutableExtensionRegistry.createRegistryFrom(parentRegistry, extensionTypes);
	}

	/**
	 * Register extensions using the supplied registrar from fields in the supplied
	 * class that are meta-annotated with {@link ExtendWith @ExtendWith} or
	 * annotated with {@link RegisterExtension @RegisterExtension}.
	 *
	 * <p>The extensions will be sorted according to {@link Order @Order} semantics
	 * prior to registration.
	 *
	 * @param registrar the registrar with which to register the extensions; never {@code null}
	 * @param clazz the class or interface in which to find the fields; never {@code null}
	 * @param instance the instance of the supplied class; may be {@code null}
	 * when searching for {@code static} fields in the class
	 */
	static void registerExtensionsFromFields(ExtensionRegistrar registrar, Class<?> clazz, Object instance) {
		Preconditions.notNull(registrar, "ExtensionRegistrar must not be null");
		Preconditions.notNull(clazz, "Class must not be null");

		Predicate<Field> predicate = (instance == null ? ReflectionUtils::isStatic : ReflectionUtils::isNotStatic);

		// Ensure that the list is modifiable, since findFields() returns an unmodifiable list.
		List<Field> fields = new ArrayList<>(findFields(clazz, predicate, TOP_DOWN));

		// Sort fields based on @Order.
		fields.sort(orderComparator);

		// @formatter:off
		fields.forEach(field -> {
			findRepeatableAnnotations(field, ExtendWith.class).stream()
				.map(ExtendWith::value)
				.flatMap(Arrays::stream)
				.forEach(registrar::registerExtension);
		});

		fields.stream()
			.filter(field -> isAnnotated(field, RegisterExtension.class))
			.forEach(field -> {
				Preconditions.condition(isNotPrivate(field), () -> String.format(
					"Failed to register extension via @RegisterExtension field [%s]: field must not be private.",
					field));
				tryToReadFieldValue(field, instance).ifSuccess(value -> {
					Preconditions.condition(value instanceof Extension, () -> String.format(
						"Failed to register extension via @RegisterExtension field [%s]: field value's type [%s] must implement an [%s] API.",
						field, (value != null ? value.getClass().getName() : null), Extension.class.getName()));
					registrar.registerExtension((Extension) value, field);
				});
			});
		// @formatter:on
	}

	/**
	 * Register extensions using the supplied registrar from parameters in the
	 * declared constructor of the supplied class that are annotated with
	 * {@link ExtendWith @ExtendWith}.
	 *
	 * @param registrar the registrar with which to register the extensions; never {@code null}
	 * @param clazz the class in which to find the declared constructor; never {@code null}
	 * @since 5.8
	 */
	static void registerExtensionsFromConstructorParameters(ExtensionRegistrar registrar, Class<?> clazz) {
		registerExtensionsFromExecutableParameters(registrar, getDeclaredConstructor(clazz));
	}

	/**
	 * Register extensions using the supplied registrar from parameters in the
	 * supplied {@link Executable} (i.e., a {@link java.lang.reflect.Constructor}
	 * or {@link java.lang.reflect.Method}) that are annotated with{@link ExtendWith @ExtendWith}.
	 *
	 * @param registrar the registrar with which to register the extensions; never {@code null}
	 * @param executable the constructor or method whose parameters should be searched; never {@code null}
	 * @since 5.8
	 */
	static void registerExtensionsFromExecutableParameters(ExtensionRegistrar registrar, Executable executable) {
		Preconditions.notNull(registrar, "ExtensionRegistrar must not be null");
		Preconditions.notNull(executable, "Executable must not be null");

		AtomicInteger index = new AtomicInteger();

		// @formatter:off
		Arrays.stream(executable.getParameters())
				.map(parameter -> findRepeatableAnnotations(parameter, index.getAndIncrement(), ExtendWith.class))
				.flatMap(Collection::stream)
				.map(ExtendWith::value)
				.flatMap(Arrays::stream)
				.forEach(registrar::registerExtension);
		// @formatter:on
	}

	/**
	 * @since 5.4
	 */
	private static final Comparator<Field> orderComparator = //
		Comparator.comparingInt(ExtensionUtils::getOrder);

	/**
	 * @since 5.4
	 */
	private static int getOrder(Field field) {
		return findAnnotation(field, Order.class).map(Order::value).orElse(Order.DEFAULT);
	}

}
