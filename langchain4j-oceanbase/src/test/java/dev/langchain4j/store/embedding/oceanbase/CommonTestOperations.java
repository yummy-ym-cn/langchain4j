package dev.langchain4j.store.embedding.oceanbase;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.testcontainers.oceanbase.OceanBaseCEContainer;

/**
 * A collection of operations which are shared by tests in this package.
 */
final class CommonTestOperations {

    /**
     * Model used to generate embeddings for this test. The all-MiniLM-L6-v2 model is chosen for consistency with other
     * implementations of EmbeddingStoreIT.
     */
    private static final EmbeddingModel EMBEDDING_MODEL = new AllMiniLmL6V2QuantizedEmbeddingModel();

    /** Name of a database table used by tests */
    public static final String TABLE_NAME = "LANGCHAIN4J_EMBEDDING_STORE";

    private static final HikariDataSource DATA_SOURCE = new HikariDataSource();
    private static final OceanBaseCEContainer OB_VECTOR = new OceanBaseCEContainer("oceanbase/oceanbase-ce:latest");

    /**
     * Seed for random numbers. When a test fails, "-Ddev.langchain4j.store.embedding.oceanbase.SEED=..." can be used to
     * re-execute it with the same random numbers.
     */
    private static final long SEED =
            Long.getLong("dev.langchain4j.store.embedding.oceanbase.SEED", System.currentTimeMillis());

    /**
     * Used to generate random numbers, such as those for an embedding vector.
     */
    private static final Random RANDOM = new Random(SEED);

    static {
        Logger.getLogger(CommonTestOperations.class.getName())
                .info("dev.langchain4j.store.embedding.oceanbase.SEED=" + SEED);

        try {
            String urlFromEnv = System.getenv("OCEANBASE_JDBC_URL");
            String username = System.getenv("OCEANBASE_JDBC_USER");
            String password = System.getenv("OCEANBASE_JDBC_PASSWORD");

            if (urlFromEnv == null) {
                // Start test container
                OB_VECTOR.withStartupTimeout(Duration.ofMinutes(5));
                OB_VECTOR.start();

                urlFromEnv = OB_VECTOR.getJdbcUrl();
                username = OB_VECTOR.getUsername();
                password = OB_VECTOR.getPassword();
            }

            initDataSource(DATA_SOURCE, urlFromEnv, username, password);

        } catch (SQLException sqlException) {
            throw new AssertionError(sqlException);
        }
    }

    private CommonTestOperations() {}

    static void initDataSource(HikariDataSource dataSource, String url, String username, String password)
            throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.oceanbase.jdbc.Driver");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        // 连接池大小配置 - 减少连接数
        config.setMaximumPoolSize(3); // 最大连接数设为3（默认通常是10）
        config.setMinimumIdle(1); // 最小空闲连接数设为1（默认与maximumPoolSize相同）
        config.setConnectionTimeout(30000); // 连接超时30秒
        config.setIdleTimeout(600000); // 空闲连接超时10分钟后关闭
        config.setMaxLifetime(1800000); // 连接最大生命周期30分钟

        DataSource newDataSource = new HikariDataSource(config);

        // Set vector memory limit
        try (Statement statement = newDataSource.getConnection().createStatement()) {
            statement.execute("ALTER SYSTEM SET ob_vector_memory_limit_percentage = 30;");
        } catch (Exception e) {
            System.out.println("Failed to set ob_vector_memory_limit_percentage: " + e.getMessage());
        }

        dataSource.setDataSource(newDataSource);
    }

    static EmbeddingModel getEmbeddingModel() {
        return EMBEDDING_MODEL;
    }

    static DataSource getDataSource() {
        return DATA_SOURCE;
    }

    /**
     * Returns the test container instance for advanced usage.
     *
     * @return The OceanBase test container. Not null.
     */
    static OceanBaseCEContainer getTestContainer() {
        return OB_VECTOR;
    }

    /**
     * Returns an embedding store configured to use a table with the common {@link #TABLE_NAME}. Any existing table
     * with this name is dropped and recreated.
     *
     * @return An embedding store configured to use a new table. Not null.
     */
    static OceanBaseEmbeddingStore newEmbeddingStore(int dimensions) throws SQLException {
        return OceanBaseEmbeddingStore.builder()
                .dataSource(getDataSource())
                .tableName(TABLE_NAME)
                .dimensions(dimensions)
                .distanceType(OceanBaseEmbeddingStore.DistanceType.EUCLIDEAN)
                .indexType(OceanBaseEmbeddingStore.IndexType.HNSW)
                .libraryType(OceanBaseEmbeddingStore.LibType.VSAG)
                .build();
    }


    /**
     * Drops the table and index created by an embedding store. Tests can call this method in
     * {@link org.junit.jupiter.api.AfterAll} or {@link org.junit.jupiter.api.AfterEach} methods to their tables.
     *
     * @param tableName Name of table to drop. Not null.
     *
     * @throws SQLException If a database error prevents the drop.
     */
    static void dropTable(String tableName) throws SQLException {
        try (Connection connection = DATA_SOURCE.getConnection();
                Statement statement = connection.createStatement()) {
            statement.addBatch("DROP INDEX IF EXISTS " + tableName + "_EMBEDDING_INDEX");
            statement.addBatch("DROP TABLE IF EXISTS " + tableName);
            statement.executeBatch();
        }
    }

    /**
     * Returns an array of random floats, which can be used to generate test embedding vectors.
     *
     * @param length Array length.
     * @return Array of random floats. Not null.
     */
    static float[] randomFloats(int length) {
        float[] floats = new float[length];
        for (int i = 0; i < floats.length; i++) floats[i] = RANDOM.nextFloat();
        return floats;
    }

    /**
     * Verifies that {@link EmbeddingStore#search(EmbeddingSearchRequest)} returns the correct result when
     * {@link OceanBaseEmbeddingStore.Builder} configurations. Callers of this method should ensure that the table contains
     * no rows before this method is called.
     *
     * @param embeddingStore Embedding store to verify. Not null.
     */
    static void verifySearch(EmbeddingStore<TextSegment> embeddingStore) {
        float[] vector0 = CommonTestOperations.randomFloats(512);
        float[] vector1 = vector0.clone();

        // Only higher indexes are increased in order to effect the cosine angle, and not just magnitude
        for (int i = 0; i < vector1.length / 2; i++) vector1[i] += 0.1f;

        List<Embedding> embeddings = new ArrayList<>(2);
        embeddings.add(Embedding.from(vector0));
        embeddings.add(Embedding.from(vector1));

        // Add the two vectors
        List<String> ids = embeddingStore.addAll(embeddings);

        // Search for the first vector
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(vector1))
                .build();

        // Verify the first vector is matched
        EmbeddingMatch<TextSegment> match =
                embeddingStore.search(request).matches().get(0);
        assertThat(match.embeddingId()).isEqualTo(ids.get(1));
        assertThat(match.embedding().vector()).containsExactly(vector1);
    }
}
