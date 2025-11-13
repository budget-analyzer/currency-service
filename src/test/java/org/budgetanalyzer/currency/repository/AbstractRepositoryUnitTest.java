package org.budgetanalyzer.currency.repository;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.TestPropertySource;

/**
 * Base class for repository unit tests using {@code @DataJpaTest} with H2 in-memory database.
 *
 * <p>This base class provides:
 *
 * <ul>
 *   <li>H2 in-memory database for fast test execution
 *   <li>JPA auditing enabled for timestamp testing
 *   <li>Automatic transaction rollback after each test
 *   <li>No dependency on Flyway migrations or seed data
 * </ul>
 *
 * <p><b>Purpose:</b>
 *
 * <p>Unit tests focus on JPA query method correctness and basic CRUD operations. They complement
 * the existing integration tests (which validate database constraints, cascade behavior, and
 * PostgreSQL-specific features).
 *
 * <p><b>Test Data Strategy:</b>
 *
 * <p>Each test creates its own entities inline without relying on Flyway seed data. This ensures
 * tests are self-contained and can run independently.
 *
 * <p><b>Comparison with Integration Tests:</b>
 *
 * <table border="1">
 *   <tr>
 *     <th>Aspect</th>
 *     <th>Unit Tests (this class)</th>
 *     <th>Integration Tests</th>
 *   </tr>
 *   <tr>
 *     <td>Speed</td>
 *     <td>Fast (< 1 second per test)</td>
 *     <td>Slower (2-5 seconds per test)</td>
 *   </tr>
 *   <tr>
 *     <td>Database</td>
 *     <td>H2 in-memory</td>
 *     <td>PostgreSQL via TestContainers</td>
 *   </tr>
 *   <tr>
 *     <td>Flyway</td>
 *     <td>Disabled</td>
 *     <td>Enabled (full migration history)</td>
 *   </tr>
 *   <tr>
 *     <td>Seed Data</td>
 *     <td>None (tests create own data)</td>
 *     <td>23 currencies from V6 migration</td>
 *   </tr>
 *   <tr>
 *     <td>Test Focus</td>
 *     <td>Query logic, basic CRUD</td>
 *     <td>Constraints, cascade, DB features</td>
 *   </tr>
 * </table>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * @DataJpaTest
 * class MyRepositoryTest extends AbstractRepositoryUnitTest {
 *
 *     @Autowired
 *     private MyRepository myRepository;
 *
 *     @Test
 *     void testQueryMethod() {
 *         // Arrange: Create test data inline
 *         var entity = new MyEntity();
 *         entity.setField("value");
 *         myRepository.save(entity);
 *
 *         // Act
 *         var result = myRepository.findByField("value");
 *
 *         // Assert
 *         assertThat(result).isPresent();
 *     }
 * }
 * }</pre>
 *
 * @see org.budgetanalyzer.currency.base.AbstractIntegrationTest
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@EnableJpaAuditing
@TestPropertySource(
    properties = {
      "spring.flyway.enabled=false", // No migrations needed for unit tests
      "spring.jpa.hibernate.ddl-auto=create-drop" // Let Hibernate create schema from entities
    })
public abstract class AbstractRepositoryUnitTest {
  // No common setup needed - each test creates its own data
}
