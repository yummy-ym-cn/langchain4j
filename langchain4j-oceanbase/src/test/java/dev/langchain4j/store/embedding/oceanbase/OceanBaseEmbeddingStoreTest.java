package dev.langchain4j.store.embedding.oceanbase;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreWithFilteringIT;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThan;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OceanBaseEmbeddingStoreTest extends EmbeddingStoreWithFilteringIT {

    EmbeddingStore<TextSegment> embeddingStore;

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

    @Override
    protected void ensureStoreIsReady() {
        try {
            // Use CommonTestOperations to create embedding store
            embeddingStore = CommonTestOperations.newEmbeddingStore(384);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize embedding store", e);
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
        return CommonTestOperations.getEmbeddingModel();
    }
}
