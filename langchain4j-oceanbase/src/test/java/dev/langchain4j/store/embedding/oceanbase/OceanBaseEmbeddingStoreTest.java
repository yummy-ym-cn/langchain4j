package dev.langchain4j.store.embedding.oceanbase;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreWithFilteringIT;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThan;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.oceanbase.OceanBaseCEContainer;

import javax.sql.DataSource;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OceanBaseEmbeddingStoreTest extends EmbeddingStoreWithFilteringIT {

    @Container
    static OceanBaseCEContainer obVector =
            new OceanBaseCEContainer("oceanbase/oceanbase-ce:latest");

    EmbeddingStore<TextSegment> embeddingStore;

    EmbeddingModel embeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel();

    @Override
    @ParameterizedTest
    @MethodSource("should_filter_by_metadata_oceanbase")
    protected void should_filter_by_metadata(
            Filter metadataFilter, List<Metadata> matchingMetadatas, List<Metadata> notMatchingMetadatas) {
        super.should_filter_by_metadata(metadataFilter, matchingMetadatas, notMatchingMetadatas);
    }

    protected static Stream<Arguments> should_filter_by_metadata_oceanbase() {
        return EmbeddingStoreWithFilteringIT.should_filter_by_metadata().filter(arguments -> {
            Filter filter = (Filter) arguments.get()[0];
            if (filter instanceof IsLessThan) {
                return ((IsLessThan) filter).comparisonValue() instanceof Number;
            } else if (filter instanceof IsLessThanOrEqualTo) {
                return ((IsLessThanOrEqualTo) filter).comparisonValue() instanceof Number;
            } else if (filter instanceof IsGreaterThan) {
                return ((IsGreaterThan) filter).comparisonValue() instanceof Number;
            } else if (filter instanceof IsGreaterThanOrEqualTo) {
                return ((IsGreaterThanOrEqualTo) filter).comparisonValue() instanceof Number;
            } else {
                return true;
            }
        });
    }

    @Test
    void add() {
    }

    @Test
    void testAdd() {
    }

    @Test
    void testAdd1() {
    }

    @Test
    void addAll() {
    }

    @Test
    void testAddAll() {
    }

    @Test
    void search() {
    }

    @Override
    protected void ensureStoreIsReady() {
        HikariConfig config = new HikariConfig();
        obVector.withStartupTimeout(Duration.ofMinutes(5));
        obVector.start();
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(obVector.getJdbcUrl()); // 数据库URL
        config.setUsername(obVector.getUsername()); // 数据库用户名
        config.setPassword(obVector.getPassword()); // 数据库密码
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.setConnectionTimeout(300000);

        // 创建数据源
        DataSource dataSource = new HikariDataSource(config);

        // 设置ALTER SYSTEM SET ob_vector_memory_limit_percentage = 30;
        try {
            final Statement statement = dataSource.getConnection().createStatement();
            statement.execute("ALTER SYSTEM SET ob_vector_memory_limit_percentage = 30;");
        } catch (Exception e) {
            System.out.println("change ob_vector_memory_limit_percentage failed!");
        }

        try {
            embeddingStore = new OceanBaseEmbeddingStore(dataSource,
                            "t1", 384,
                            OceanBaseEmbeddingStore.DistanceType.EUCLIDEAN,
                            OceanBaseEmbeddingStore.IndexType.HNSW,
                            OceanBaseEmbeddingStore.LibType.VSAG);
        } catch (Exception e) {
            System.out.println("init embeddingStore failed, " + Arrays.toString(e.getStackTrace()));
        }
    }

    @Override
    protected void clearStore() {
        embeddingStore.removeAll();
    }

    @Override
    protected EmbeddingStore<TextSegment> embeddingStore() {
        return embeddingStore;
    }

    @Override
    protected EmbeddingModel embeddingModel() {
        return embeddingModel;
    }
}
