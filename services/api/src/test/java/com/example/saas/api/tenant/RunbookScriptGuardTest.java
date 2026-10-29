package com.example.saas.api.tenant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every operator script that writes quits before its first SQL statement unless psql runs with ON_ERROR_STOP on: its
 * guards stop it only by raising an error, and psql carries on after an error otherwise. The behaviour itself is checked
 * with real psql (see the test records in the runbooks); this test keeps the guard from being removed or moved down.
 */
class RunbookScriptGuardTest {

	private static final List<String> WRITING_SCRIPTS = List.of("operator-assign-plan.sql", "operator-ownership-transfer.sql",
			"disable-account-and-flag-companies.sql", "reverse-account-disable.sql");

	@Test
	void everyWritingScriptQuitsBeforeItsFirstStatementUnlessOnErrorStopIsOn() throws IOException {
		for (String name : WRITING_SCRIPTS) {
			List<String> lines = Files.readAllLines(
					Path.of("..", "..", "docs", "runbooks", "operations", "sql", name));
			int firstStatement = -1;
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).strip();
				if (!line.isEmpty() && !line.startsWith("--") && !line.startsWith("\\")) {
					firstStatement = i;
					break;
				}
			}
			assertThat(firstStatement).as(name).isPositive();
			List<String> before = lines.subList(0, firstStatement).stream().map(String::strip).toList();
			assertThat(before).as(name).contains("\\if :{?ON_ERROR_STOP}", "\\if :ON_ERROR_STOP");
			assertThat(before.stream().filter("\\quit"::equals).count()).as(name).isEqualTo(2);
		}
	}

}
