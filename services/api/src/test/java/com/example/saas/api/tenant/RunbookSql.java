package com.example.saas.api.tenant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads an operator script from the runbooks and turns it into statements the tests can run through JDBC: comment lines
 * dropped, each psql variable ({@code :'name'}) replaced by a quoted literal, one element per statement. A statement ends
 * at a semicolon at the end of a line; the scripts keep semicolons out of their comments so this is safe.
 */
public final class RunbookSql {

	private RunbookSql() {
	}

	public static List<String> statements(Path script, Map<String, String> variables) throws IOException {
		StringBuilder current = new StringBuilder();
		List<String> out = new ArrayList<>();
		for (String line : Files.readAllLines(script)) {
			if (line.strip().startsWith("--")) {
				continue;
			}
			String sql = line;
			for (Map.Entry<String, String> variable : variables.entrySet()) {
				sql = sql.replace(":'" + variable.getKey() + "'", "'" + variable.getValue().replace("'", "''") + "'");
			}
			current.append(sql).append('\n');
			if (line.stripTrailing().endsWith(";")) {
				out.add(current.toString());
				current.setLength(0);
			}
		}
		if (!current.toString().isBlank()) {
			throw new IllegalStateException("The script " + script + " ends with an incomplete statement");
		}
		return out;
	}

}
