/*
 * Copyright 2015-2021 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.jupiter.params.provider;

import static org.junit.jupiter.params.provider.CsvParserFactory.createParserFor;
import static org.junit.platform.commons.util.CollectionUtils.toSet;

import java.io.StringReader;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.univocity.parsers.csv.CsvParser;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.support.AnnotationConsumer;
import org.junit.platform.commons.PreconditionViolationException;
import org.junit.platform.commons.util.Preconditions;
import org.junit.platform.commons.util.UnrecoverableExceptions;

/**
 * @since 5.0
 */
class CsvArgumentsProvider implements ArgumentsProvider, AnnotationConsumer<CsvSource> {

	private static final String LINE_SEPARATOR = "\n";

	private CsvSource annotation;
	private Set<String> nullValues;
	private CsvParser csvParser;

	@Override
	public void accept(CsvSource annotation) {
		this.annotation = annotation;
		this.nullValues = toSet(annotation.nullValues());
		this.csvParser = createParserFor(annotation);
	}

	@Override
	public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
		final boolean textBlockDeclared = !this.annotation.textBlock().isEmpty();
		Preconditions.condition(this.annotation.value().length > 0 ^ textBlockDeclared,
			() -> "@CsvSource must be declared with either `value` or `textBlock` but not both");

		return textBlockDeclared ? parseTextBlock() : parseValueArray();
	}

	private Stream<Arguments> parseTextBlock() {
		String textBlock = this.annotation.textBlock();
		boolean useHeadersInDisplayName = this.annotation.useHeadersInDisplayName();
		List<Arguments> argumentsList = new ArrayList<>();

		try {
			List<String[]> csvRecords = this.csvParser.parseAll(new StringReader(textBlock));
			String[] headers = useHeadersInDisplayName ? getHeaders(this.csvParser) : null;

			AtomicInteger index = new AtomicInteger(0);
			for (String[] csvRecord : csvRecords) {
				index.incrementAndGet();
				Preconditions.notNull(csvRecord,
					() -> "Record at index " + index + " contains invalid CSV: \"\"\"\n" + textBlock + "\n\"\"\"");
				argumentsList.add(processCsvRecord(csvRecord, this.nullValues, useHeadersInDisplayName, headers));
			}
		}
		catch (Throwable throwable) {
			throw handleCsvException(throwable, this.annotation);
		}

		return argumentsList.stream();
	}

	private Stream<Arguments> parseValueArray() {
		boolean useHeadersInDisplayName = this.annotation.useHeadersInDisplayName();
		List<Arguments> argumentsList = new ArrayList<>();

		try {
			String[] headers = null;
			AtomicInteger index = new AtomicInteger(0);
			for (String input : this.annotation.value()) {
				index.incrementAndGet();
				String[] csvRecord = this.csvParser.parseLine(input + LINE_SEPARATOR);
				// Lazily retrieve headers if necessary.
				if (useHeadersInDisplayName && headers == null) {
					headers = getHeaders(this.csvParser);
				}
				Preconditions.notNull(csvRecord,
					() -> "Record at index " + index + " contains invalid CSV: \"" + input + "\"");
				argumentsList.add(processCsvRecord(csvRecord, this.nullValues, useHeadersInDisplayName, headers));
			}
		}
		catch (Throwable throwable) {
			throw handleCsvException(throwable, this.annotation);
		}

		return argumentsList.stream();
	}

	// Cannot get parsed headers until after parsing has started.
	static String[] getHeaders(CsvParser csvParser) {
		return Arrays.stream(csvParser.getContext().parsedHeaders())//
				.map(String::trim)//
				.toArray(String[]::new);
	}

	/**
	 * Processes custom null values, supports wrapping of column values in
	 * {@link Named} if necessary (for CSV header support), and returns the
	 * CSV record wrapped in an {@link Arguments} instance.
	 */
	static Arguments processCsvRecord(Object[] csvRecord, Set<String> nullValues, boolean useHeadersInDisplayName,
			String[] headers) {

		// Nothing to process?
		if (nullValues.isEmpty() && !useHeadersInDisplayName) {
			return Arguments.of(csvRecord);
		}

		Preconditions.condition(!useHeadersInDisplayName || (csvRecord.length <= headers.length),
			() -> String.format("The number of columns exceeds the number of supplied headers (%d) in CSV record: %s",
				headers.length, Arrays.toString(csvRecord)));

		Object[] arguments = new Object[csvRecord.length];
		for (int i = 0; i < csvRecord.length; i++) {
			Object column = csvRecord[i];
			if (nullValues.contains(column)) {
				column = null;
			}
			if (useHeadersInDisplayName) {
				column = Named.of(headers[i] + " = " + column, column);
			}
			arguments[i] = column;
		}
		return Arguments.of(arguments);
	}

	/**
	 * @return this method always throws an exception and therefore never
	 * returns anything; the return type is merely present to allow this
	 * method to be supplied as the operand in a {@code throw} statement
	 */
	static RuntimeException handleCsvException(Throwable throwable, Annotation annotation) {
		UnrecoverableExceptions.rethrowIfUnrecoverable(throwable);
		if (throwable instanceof PreconditionViolationException) {
			throw (PreconditionViolationException) throwable;
		}
		throw new CsvParsingException("Failed to parse CSV input configured via " + annotation, throwable);
	}

}
