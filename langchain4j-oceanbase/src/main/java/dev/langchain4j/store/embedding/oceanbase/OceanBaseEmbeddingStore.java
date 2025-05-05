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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public class OceanBaseEmbeddingStore implements EmbeddingStore<TextSegment> {

    private static final Logger log = LoggerFactory.getLogger(OceanBaseEmbeddingStore.class);
    private final DataSource   dataSource;

    private final String       tableName;

    private final int          dimensions;

    private final DistanceType distanceType;

    private final IndexType    indexType;

    private final LibType      libraryType;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public OceanBaseEmbeddingStore(DataSource dataSource, String tableName, int dimensions,
                                   DistanceType distanceType, IndexType indexType,
                                   LibType libraryType) throws SQLException {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.dimensions = dimensions;
        this.distanceType = distanceType;
        this.indexType = indexType;
        this.libraryType = libraryType;

        String createTableSqlTemplate = String
                .format(
                        " CREATE TABLE IF NOT EXISTS %s ( " + "id char(36) PRIMARY KEY, "
                                + "content text, " + "metadata json, " + "embedding vector(%s), "
                                + "vector key `embedding_idx` " + "(`embedding`) " + "with ( "
                                + "distance=%s, type=%s, lib=%s, m=%s, ef_construction=%s, ef_search=%s"
                                + "))", this.tableName, this.dimensions, this.distanceType.distanceOptName,
                        this.indexType.name(), this.libraryType, 16, 200, 64);
        Connection connection = null;
        Statement statement = null;
        try {
            connection = dataSource.getConnection();
            statement = connection.createStatement();
            statement.execute(createTableSqlTemplate);
        } catch (SQLException e) {
            log.error("Failed to create table", e);
        } finally {
            assert statement != null;
            statement.close();
            connection.close();
        }

    }

    @Override
    public void removeAll() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            String deleteAllSqlTemplate = "delete from %s";
            String deleteAllSql = String.format(deleteAllSqlTemplate, tableName);
            PreparedStatement delete = connection.prepareStatement(deleteAllSql);
            delete.execute();
        } catch (Exception e) {
            throw new RuntimeException("remove all embeddings failed!", e);
        }

    }

    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException("method not implemented yet");
    }

    @Override
    public void add(String s, Embedding embedding) {
        TextSegment textSegment = TextSegment.textSegment(s);
        add(embedding, textSegment);
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        List<String> list = addAll(Collections.singletonList(embedding),
                Collections.singletonList(textSegment));
        if (list == null || list.isEmpty()) {
            throw new IllegalStateException("add failed for embedding: " + embedding);
        }
        return list.stream().findFirst().get();
    }

    @Override
    public List<String> addAll(List<Embedding> list) {
        throw new UnsupportedOperationException("method not implemented yet");
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        if (embeddings.size() != textSegments.size()) {
            throw new IllegalArgumentException("embeddings.size() " + embeddings.size()
                    + " is not equal to embedded.size() " + textSegments.size());
        }
        List<String> ids = new ArrayList<>();
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            String insertSqlTemplate = "insert ignore into %s (id, content, metadata, embedding) values (?, ?, ?, ?)";
            String insertSql = String.format(insertSqlTemplate, tableName);
            PreparedStatement insert = connection.prepareStatement(insertSql);
            for (int i = 0; i < embeddings.size(); i++) {
                String metaDataJson = objectMapper.writeValueAsString(textSegments.get(i).metadata().toMap());
                String id = String.valueOf(UUID.randomUUID());
                insert.setString(1, id);
                insert.setString(2, textSegments.get(i).text());
                insert.setString(3, metaDataJson);
                insert.setString(4, Arrays.toString(embeddings.get(i).vector()));
                insert.addBatch();
                ids.add(id);
            }
            insert.executeBatch();

        } catch (JsonProcessingException | SQLException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                assert connection != null;
                connection.close();
            } catch (SQLException e) {
                log.error("Failed to close connection", e);
            }
        }
        return ids;
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        Filter filter = request.filter();
        String filterExpression = null;
        if (filter != null) {
            filterExpression = ObVectorFilterMapper.map(filter);
        }

        // debug
        String selectAllSql = String.format("SELECT * FROM %s", tableName);

        String selectSqlTemplate = "SELECT * FROM %s WHERE 1=1 %s ORDER BY %s(embedding, '%s') APPROXIMATE LIMIT %s";
        String sql = String.format(selectSqlTemplate,
                tableName,
                filterExpression != null ? " AND "+filterExpression:"",
                distanceType.distanceFunction,
                Arrays.toString(request.queryEmbedding().vector()),
                request.maxResults()
        );
        try {
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>(request.maxResults());

            ResultSet resultSetAll = statement.executeQuery(selectAllSql);
            while (resultSetAll.next()) {
                String metaDataJson = resultSetAll.getString("metadata");
                System.out.println("metaDataJson: " + metaDataJson);
            }

            ResultSet resultSet = statement.executeQuery(sql);
            while (resultSet.next()) {
                String id = resultSet.getString("id");
                List<Double> embeddingList = objectMapper.readValue(resultSet.getString("embedding"), new TypeReference<List<Double>>(){});
                float[] embeddingArray = new float[embeddingList.size()];
                for (int i = 0; i < embeddingList.size(); i++) {
                    embeddingArray[i] = embeddingList.get(i).floatValue();
                }
                String content = resultSet.getString("content");
                String metaDataJson = resultSet.getString("metadata");
                HashMap<String, Object> hashMap = objectMapper.readValue(metaDataJson, HashMap.class);
                Metadata metadata = new Metadata(hashMap);
                EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(1.0, id,
                        new Embedding(embeddingArray),
                        content == null ? null : new TextSegment(content, metadata));
                matches.add(match);
            }
            return new EmbeddingSearchResult<>(matches);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
        HNSW,
    }

    public enum LibType {
        VSAG,
    }
}
