package cafe.discosticker.api.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import cafe.discosticker.api.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationIT {

	@Autowired
	Flyway flyway;

	@Autowired
	DataSource dataSource;

	@Test
	void flywayRanWithNoPendingMigrations() throws Exception {
		assertThat(flyway.info().pending()).isEmpty();
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).startsWith("jdbc:postgresql:");
		}
	}

}
