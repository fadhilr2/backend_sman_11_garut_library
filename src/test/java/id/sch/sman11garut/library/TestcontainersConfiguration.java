package id.sch.sman11garut.library;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A throwaway MySQL for integration tests, started in Docker by Testcontainers.
 * {@code @ServiceConnection} points the app's datasource at it, so the dev database is never touched.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		// Pinned to the production major version (MySQL 8), not "latest".
		return new MySQLContainer(DockerImageName.parse("mysql:8.0"));
	}

}
