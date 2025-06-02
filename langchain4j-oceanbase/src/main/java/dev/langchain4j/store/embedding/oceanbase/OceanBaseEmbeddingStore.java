package dev.langchain4j.store.embedding.oceanbase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OceanBaseEmbeddingStore implements EmbeddingStore<TextSegment> {

    private static final Logger log = LoggerFactory.getLogger(OceanBaseEmbeddingStore.class);
    private final DataSource dataSource;
    private final String tableName;
    private final int dimensions;
    private final DistanceType distanceType;
    private final IndexType indexType;
    private final LibType libraryType;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OceanBaseEmbeddingStore(
            DataSource dataSource,
            String tableName,
            int dimensions,
            DistanceType distanceType,
            IndexType indexType,
            LibType libraryType)
            throws SQLException {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.dimensions = dimensions;
        this.distanceType = distanceType;
        this.indexType = indexType;
        this.libraryType = libraryType;

        String createTableSqlTemplate = String.format(
                "CREATE TABLE IF NOT EXISTS %s ("
                        + "id char(36) PRIMARY KEY, "
                        + "content text, "
                        + "metadata json, "
                        + "embedding vector(%s), "
                        + "vector key `embedding_idx` (`embedding`) "
                        + "with ("
                        + "distance=%s, type=%s, lib=%s, m=%s, ef_construction=%s, ef_search=%s"
                        + "))",
                this.tableName,
                this.dimensions,
                this.distanceType.distanceOptName,
                this.indexType.name(),
                this.libraryType,
                16,
                200,
                64);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(createTableSqlTemplate);
        } catch (SQLException e) {
            log.error("Failed to create table", e);
            throw e;
        }
    }

    @Override
    public void removeAll() {
        try (Connection connection = dataSource.getConnection()) {
            String deleteAllSqlTemplate = "DELETE FROM %s";
            String deleteAllSql = String.format(deleteAllSqlTemplate, tableName);
            try (PreparedStatement delete = connection.prepareStatement(deleteAllSql)) {
                delete.execute();
            }
        } catch (Exception e) {
            throw new RuntimeException("remove all embeddings failed!", e);
        }
    }

    @Override
    public String add(Embedding embedding) {
        List<String> ids = addAllInternal(null, List.of(embedding), null);
        return ids.get(0);
    }

    @Override
    public void add(String id, Embedding embedding) {
        addAllInternal(List.of(id), List.of(embedding), null);
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        List<String> ids = addAllInternal(null, List.of(embedding), List.of(textSegment));
        return ids.get(0);
    }

    public void add(String id, Embedding embedding, TextSegment textSegment) {
        addAllInternal(List.of(id), List.of(embedding), List.of(textSegment));
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return addAllInternal(null, embeddings, null);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        return addAllInternal(null, embeddings, textSegments);
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        addAllInternal(ids, embeddings, embedded);
    }

    /**
     * Internal method to handle all variants of adding embeddings to the database.
     *
     * @param ids Optional list of IDs to use. If null, UUIDs will be generated.
     * @param embeddings List of embeddings to add.
     * @param textSegments Optional list of text segments. If null, only embeddings are stored.
     * @return List of IDs used for the inserted records.
     */
    private List<String> addAllInternal(List<String> ids, List<Embedding> embeddings, List<TextSegment> textSegments) {
        if (embeddings == null || embeddings.isEmpty()) {
            return new ArrayList<>();
        }

        // Validate input sizes
        if (textSegments != null && embeddings.size() != textSegments.size()) {
            throw new IllegalArgumentException("embeddings.size() " + embeddings.size()
                    + " is not equal to textSegments.size() " + textSegments.size());
        }
        if (ids != null && embeddings.size() != ids.size()) {
            throw new IllegalArgumentException("All lists must have the same size. ids.size() = "
                    + ids.size() + ", embeddings.size() = " + embeddings.size()
                    + (textSegments != null ? ", textSegments.size() = " + textSegments.size() : ""));
        }

        // Generate IDs if not provided
        List<String> resultIds = ids != null ? new ArrayList<>(ids) : new ArrayList<>();
        if (ids == null) {
            for (int i = 0; i < embeddings.size(); i++) {
                resultIds.add(UUID.randomUUID().toString());
            }
        }

        // Choose SQL template based on whether text segments are provided
        boolean hasTextSegments = textSegments != null;
        String insertSqlTemplate = hasTextSegments
                ? "INSERT IGNORE INTO %s (id, content, metadata, embedding) VALUES (?, ?, ?, ?)"
                : "INSERT IGNORE INTO %s (id, embedding) VALUES (?, ?)";
        String insertSql = String.format(insertSqlTemplate, tableName);

        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement(insertSql)) {

            for (int i = 0; i < embeddings.size(); i++) {
                insert.setString(1, resultIds.get(i));

                if (hasTextSegments) {
                    TextSegment segment = textSegments.get(i);
                    insert.setString(2, segment.text());
                    insert.setString(3, serializeMetadata(segment.metadata()));
                    insert.setString(4, formatVector(embeddings.get(i).vector()));
                } else {
                    insert.setString(2, formatVector(embeddings.get(i).vector()));
                }

                insert.addBatch();
            }

            insert.executeBatch();
            return resultIds;

        } catch (SQLException | JsonProcessingException e) {
            String operation = hasTextSegments ? "add embeddings with text segments" : "add embeddings";
            throw new RuntimeException("Failed to " + operation, e);
        }
    }

    /**
     * Serialize metadata to JSON string.
     */
    private String serializeMetadata(Metadata metadata) throws JsonProcessingException {
        return objectMapper.writeValueAsString(metadata.toMap());
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        Filter filter = request.filter();
        String filterExpression = null;
        if (filter != null) {
            filterExpression = ObVectorFilterMapper.map(filter);
        }

        String selectSqlTemplate =
                "SELECT id, content, metadata, embedding, %s(embedding, '%s') as distance FROM %s WHERE 1=1 %s ORDER BY distance LIMIT %s";
        String sql = String.format(
                selectSqlTemplate,
                distanceType.distanceFunction,
                formatVector(request.queryEmbedding().vector()),
                tableName,
                filterExpression != null ? " AND " + filterExpression : "",
                request.maxResults());
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>(request.maxResults());

            try (ResultSet resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    String id = resultSet.getString("id");
                    String embeddingStr = resultSet.getString("embedding");
                    float[] embeddingArray = parseVector(embeddingStr);
                    String content = resultSet.getString("content");
                    String metaDataJson = resultSet.getString("metadata");
                    Double distance = resultSet.getDouble("distance");

                    // Convert distance to similarity score based on distance type
                    Double score = distanceToScore(distance);

                    // Apply minimum score filtering
                    if (score < request.minScore()) {
                        continue;
                    }

                    Map<String, String> metadata = new HashMap<>();
                    if (metaDataJson != null && !metaDataJson.isEmpty()) {
                        try {
                            metadata =
                                    objectMapper.readValue(metaDataJson, new TypeReference<Map<String, String>>() {});
                        } catch (JsonProcessingException e) {
                            log.warn("Failed to parse metadata JSON: {}", metaDataJson, e);
                        }
                    }

                    TextSegment embedded = null;
                    if (content != null) {
                        embedded = TextSegment.from(content, Metadata.from(metadata));
                    }

                    matches.add(new EmbeddingMatch<>(score, id, new Embedding(embeddingArray), embedded));
                }
            }
            return new EmbeddingSearchResult<>(matches);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts distance to similarity score based on the distance type.
     *
     * @param distance The distance value from the database
     * @return Similarity score in the range [0, 1] where 1 is most similar
     */
    private Double distanceToScore(Double distance) {
        switch (distanceType) {
            case COSINE:
                // Cosine distance is typically in range [0, 2], convert to score [1, 0]
                return 1.0 - (distance / 2.0);
            case EUCLIDEAN:
            case MANHATTAN:
                // For Euclidean and Manhattan distance, we use a simple inverse transformation
                // Since we don't know the theoretical maximum distance, we use an exponential decay
                return 1.0 / (1.0 + distance);
            case DOT:
                // Dot product distance (negative dot product) - higher values mean more similar
                // Convert negative dot product to positive similarity score
                return Math.max(0.0, Math.min(1.0, -distance + 1.0));
            default:
                // Default fallback - simple inverse transformation
                return 1.0 / (1.0 + distance);
        }
    }

    private String formatVector(float[] vector) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            // Use maximum precision to preserve all significant digits of float values
            sb.append(String.format("%.9f", (double) vector[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    private float[] parseVector(String vectorStr) {
        String[] parts = vectorStr.substring(1, vectorStr.length() - 1).split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }

    public enum DistanceType {
        EUCLIDEAN("l2_distance", "l2"),
        MANHATTAN("l1_distance", "l1"),
        COSINE("cosine_distance", "cosine_distance"),
        DOT("inner_product", "inner_product");

        public final String distanceFunction;
        public final String distanceOptName;

        DistanceType(String distanceFunction, String distanceOptName) {
            this.distanceFunction = distanceFunction;
            this.distanceOptName = distanceOptName;
        }
    }

    public enum IndexType {
        HNSW
    }

    public enum LibType {
        VSAG
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private DataSource dataSource;
        private String tableName;
        private int dimensions;
        private DistanceType distanceType = DistanceType.COSINE;
        private IndexType indexType = IndexType.HNSW;
        private LibType libraryType = LibType.VSAG;

        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        public Builder distanceType(DistanceType distanceType) {
            this.distanceType = distanceType;
            return this;
        }

        public Builder indexType(IndexType indexType) {
            this.indexType = indexType;
            return this;
        }

        public Builder libraryType(LibType libraryType) {
            this.libraryType = libraryType;
            return this;
        }

        public OceanBaseEmbeddingStore build() throws SQLException {
            if (dataSource == null) {
                throw new IllegalArgumentException("dataSource must not be null");
            }
            if (tableName == null || tableName.trim().isEmpty()) {
                throw new IllegalArgumentException("tableName must not be null or empty");
            }
            if (dimensions <= 0) {
                throw new IllegalArgumentException("dimensions must be greater than 0");
            }
            return new OceanBaseEmbeddingStore(dataSource, tableName, dimensions, distanceType, indexType, libraryType);
        }
    }
}
