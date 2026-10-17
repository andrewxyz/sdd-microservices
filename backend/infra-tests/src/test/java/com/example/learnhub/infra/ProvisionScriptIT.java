package com.example.learnhub.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * FR-007: every start runs the provisioning script, so it must be safe to run any number of times.
 *
 * <p>Run against a real PostgreSQL 16 (constitution Principle VI: Testcontainers, never H2), because
 * the behaviours that matter here are {@code CREATE DATABASE} outside a transaction and role
 * creation races, neither of which an in-memory database would reproduce.
 */
@Testcontainers
class ProvisionScriptIT {

    private static final Path SCRIPT = Path.of("..", "docker", "db-init", "provision.sh");

    private static final List<String> SERVICES =
            List.of("auth", "course", "enrollment", "payment", "certificate", "review", "notification");

    private static final String ADMIN_PASSWORD = "admin-secret";
    private static final String SERVICE_PASSWORD = "service-secret";

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startPostgres() {
        Assumptions.assumeTrue(Files.exists(SCRIPT), "provision.sh not present yet");

        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                .withUsername("learnhub")
                .withPassword(ADMIN_PASSWORD)
                .withDatabaseName("postgres");
        postgres.start();
        postgres.copyFileToContainer(MountableFile.forHostPath(SCRIPT), "/tmp/provision.sh");
    }

    private Container.ExecResult runScript(String servicePassword) throws Exception {
        String[] env = SERVICES.stream()
                .map(service -> "%s_DB_PASSWORD=%s".formatted(service.toUpperCase(), servicePassword))
                .toArray(String[]::new);

        StringBuilder command = new StringBuilder("export PGPASSWORD='" + ADMIN_PASSWORD + "' POSTGRES_USER=learnhub");
        for (String entry : env) {
            command.append(" ").append(entry);
        }
        command.append(" && sh /tmp/provision.sh");

        return postgres.execInContainer("sh", "-c", command.toString());
    }

    private List<String> query(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                     postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            List<String> rows = new java.util.ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
            return rows;
        }
    }

    @Test
    @DisplayName("running the script twice succeeds both times and creates each role and database once")
    void idempotent() throws Exception {
        Container.ExecResult first = runScript(SERVICE_PASSWORD);
        assertThat(first.getExitCode()).as("first run: %s", first.getStderr()).isZero();

        Container.ExecResult second = runScript(SERVICE_PASSWORD);
        assertThat(second.getExitCode()).as("second run: %s", second.getStderr()).isZero();

        for (String service : SERVICES) {
            assertThat(query("SELECT rolname FROM pg_roles WHERE rolname = '" + service + "'"))
                    .as("role %s exists exactly once", service).containsExactly(service);
            assertThat(query("SELECT datname FROM pg_database WHERE datname = '" + service + "'"))
                    .as("database %s exists exactly once", service).containsExactly(service);
        }
    }

    @Test
    @DisplayName("each service can log in to its own database")
    void serviceCanLogIn() throws Exception {
        runScript(SERVICE_PASSWORD);

        for (String service : SERVICES) {
            String url = "jdbc:postgresql://%s:%d/%s".formatted(
                    postgres.getHost(), postgres.getMappedPort(5432), service);
            try (Connection connection = DriverManager.getConnection(url, service, SERVICE_PASSWORD)) {
                assertThat(connection.isValid(5)).as("%s can connect to its own database", service).isTrue();
            }
        }
    }

    @Test
    @DisplayName("a changed password is re-applied on the next run")
    void reappliesPassword() throws Exception {
        runScript(SERVICE_PASSWORD);
        runScript("rotated-secret");

        String url = "jdbc:postgresql://%s:%d/auth".formatted(postgres.getHost(), postgres.getMappedPort(5432));

        try (Connection connection = DriverManager.getConnection(url, "auth", "rotated-secret")) {
            assertThat(connection.isValid(5)).as("new password works").isTrue();
        }
        assertThatThrownBy(() -> DriverManager.getConnection(url, "auth", SERVICE_PASSWORD))
                .as("old password no longer works")
                .isInstanceOf(java.sql.SQLException.class);
    }

    @Test
    @DisplayName("a service cannot connect to another service's database")
    void databasesAreIsolated() throws Exception {
        runScript(SERVICE_PASSWORD);

        String courseDb = "jdbc:postgresql://%s:%d/course".formatted(
                postgres.getHost(), postgres.getMappedPort(5432));

        assertThatThrownBy(() -> DriverManager.getConnection(courseDb, "auth", SERVICE_PASSWORD))
                .as("constitution Principle II: nothing reads another service's database")
                .isInstanceOf(java.sql.SQLException.class);
    }

    @Test
    @DisplayName("no tables are created: schemas belong to the services that own them")
    void createsNoTables() throws Exception {
        runScript(SERVICE_PASSWORD);

        String url = "jdbc:postgresql://%s:%d/auth".formatted(postgres.getHost(), postgres.getMappedPort(5432));
        try (Connection connection = DriverManager.getConnection(url, "auth", SERVICE_PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
