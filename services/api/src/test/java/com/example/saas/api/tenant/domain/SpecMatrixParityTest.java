package com.example.saas.api.tenant.domain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compares {@link CompanyPolicy} with the permission table in SPEC-TEN cell by cell (D-117), so changing the spec
 * without the code, or the code without the spec, fails the build.
 */
class SpecMatrixParityTest {

	private static final Path SPEC = Path.of("..", "..", "docs", "specs", "002-tenants", "spec.md");

	private static final List<Role> COLUMNS = List.of(Role.OWNER, Role.ADMIN, Role.AGENT, Role.VIEWER);

	static Grant parse(String cell) {
		String text = cell.strip();
		if (text.startsWith("Yes")) {
			return Grant.ALLOWED;
		}
		if (text.startsWith("No")) {
			return Grant.DENIED;
		}
		if (text.equals("Agent/Viewer only")) {
			return Grant.TARGET_AGENT_OR_VIEWER;
		}
		if (text.equals("View only")) {
			return Grant.VIEW_ONLY;
		}
		if (text.equals("System process")) {
			return Grant.SYSTEM_ONLY;
		}
		throw new AssertionError("Unrecognized matrix cell in the spec: '" + text + "'");
	}

	@Test
	void theCodeMatchesTheSpecTable() throws IOException {
		List<String> lines = Files.readAllLines(SPEC);
		int header = lines.indexOf("| Permission | Owner | Admin | Agent | Viewer |");
		assertThat(header).as("the permission table header in the spec").isPositive();

		Set<Permission> seen = EnumSet.noneOf(Permission.class);
		for (int i = header + 2; i < lines.size() && lines.get(i).startsWith("|"); i++) {
			List<String> cells = Arrays.stream(lines.get(i).split("\\|", -1)).skip(1).map(String::strip).toList();
			Permission permission = Arrays.stream(Permission.values())
				.filter(p -> p.label().equals(cells.get(0)))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No Permission for the spec row '" + cells.get(0) + "'"));
			assertThat(seen.add(permission)).as("row listed once: " + permission).isTrue();
			for (int column = 0; column < COLUMNS.size(); column++) {
				Role role = COLUMNS.get(column);
				assertThat(CompanyPolicy.grant(role, permission)).as("%s / %s", cells.get(0), role)
					.isEqualTo(parse(cells.get(column + 1)));
			}
		}
		assertThat(seen).as("every Permission has a row in the spec").containsExactlyInAnyOrder(Permission.values());
	}

}
