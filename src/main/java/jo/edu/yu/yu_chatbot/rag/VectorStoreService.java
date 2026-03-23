package jo.edu.yu.yu_chatbot.rag;

import jo.edu.yu.yu_chatbot.document.ChunkEntity;
import jo.edu.yu.yu_chatbot.document.ChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Service responsible for managing vector embeddings and performing search operations
 * (Vector, Text, and Hybrid) using Elasticsearch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VectorStoreService {

    private final RestHighLevelClient client;
    private final ChunkRepository chunkRepository;

    private static final String INDEX_NAME = "chunk_vectors";
    private static final int EXPECTED_EMBEDDING_DIM = 1024;

    /**
     * Upserts a document chunk and its vector embedding into Elasticsearch.
     */
    @Transactional(readOnly = true)
    public void upsertEmbedding(Long chunkId, float[] embedding) {
        if (chunkId == null) {
            log.warn("Chunk ID is null - skipping");
            return;
        }

        if (embedding == null || embedding.length == 0) {
            log.error("Chunk {}: embedding is NULL or EMPTY", chunkId);
            return;
        }

        if (embedding.length != EXPECTED_EMBEDDING_DIM) {
            log.error("Chunk {}: WRONG embedding size! Expected {}, got {}",
                    chunkId, EXPECTED_EMBEDDING_DIM, embedding.length);
            return;
        }

        for (float v : embedding) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                log.error("Chunk {}: embedding contains NaN or Infinity!", chunkId);
                return;
            }
        }

        ChunkEntity chunk = chunkRepository.findById(chunkId).orElse(null);
        if (chunk == null) {
            log.error("Chunk {}: Not found in DB", chunkId);
            return;
        }

        Map<String, Object> doc = new HashMap<>();
        doc.put("embedding", toList(embedding));
        doc.put("text", chunk.getText());

        if (chunk.getDocument() != null) {
            doc.put("sourceUrl", chunk.getDocument().getSourceUrl());
            doc.put("documentTitle", chunk.getDocument().getTitle());
            doc.put("contentType", chunk.getDocument().getContentType());

            String faculty = extractFaculty(chunk.getDocument().getSourceUrl());
            if (faculty != null) {
                doc.put("faculty", faculty);
            }
        }

        doc.put("chunkIndex", chunk.getChunkIndex());

        if (chunk.getSummary() != null) {
            doc.put("summary", chunk.getSummary());
        }

        if (chunk.getHeading() != null) {
            doc.put("heading", chunk.getHeading());
        }

        if (chunk.getKeywords() != null) {
            doc.put("keywords", chunk.getKeywords());
        }

        if (chunk.getImportance() != null) {
            doc.put("importance", chunk.getImportance());
        }

        IndexRequest request = new IndexRequest(INDEX_NAME)
                .id(chunkId.toString())
                .source(doc);

        try {
            client.index(request, RequestOptions.DEFAULT);
            log.debug("Chunk {} indexed successfully (Dim: {})", chunkId, EXPECTED_EMBEDDING_DIM);
        } catch (Exception e) {
            log.error("Chunk {}: Elasticsearch indexing failed: {}", chunkId, e.getMessage());
        }
    }

    private String extractFaculty(String url) {
        if (url == null) return null;
        String lower = url.toLowerCase();

        // Specific mappings for university faculties
        if (lower.contains("it.yu.edu.jo")) return "IT";
        if (lower.contains("science.yu.edu.jo")) return "SCIENCE";
        if (lower.contains("engineering.yu.edu.jo")) return "ENGINEERING";
        if (lower.contains("medicine.yu.edu.jo")) return "MEDICINE";
        if (lower.contains("pharmacy.yu.edu.jo")) return "PHARMACY";
        if (lower.contains("arts.yu.edu.jo")) return "ARTS";
        if (lower.contains("business.yu.edu.jo")) return "BUSINESS";
        if (lower.contains("sharia.yu.edu.jo")) return "SHARIA";
        if (lower.contains("education.yu.edu.jo")) return "EDUCATION";
        if (lower.contains("lawfaculty.yu.edu.jo")) return "LAW";
        if (lower.contains("nursing.yu.edu.jo")) return "NURSING";
        if (lower.contains("sports.yu.edu.jo")) return "SPORTS";
        if (lower.contains("archaeology.yu.edu.jo")) return "ARCHAEOLOGY";
        if (lower.contains("tourism.yu.edu.jo")) return "TOURISM";
        if (lower.contains("finearts.yu.edu.jo")) return "FINEARTS";
        if (lower.contains("mass.yu.edu.jo")) return "MASS";

        return null;
    }

    /**
     * Performs a semantic vector search using cosine similarity (dot product on normalized vectors).
     */
    public List<RetrievedChunk> searchSimilar(String queryText, float[] queryEmbedding, int topK) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            log.warn("Empty query embedding for: {}", queryText);
            return Collections.emptyList();
        }

        try {
            log.info("[VECTOR SEARCH] Starting for: '{}' (topK: {})", queryText, topK);

            Map<String, Object> params = new HashMap<>();
            params.put("query_vector", toList(queryEmbedding));

            Script script = new Script(
                    ScriptType.INLINE,
                    "painless",
                    "double score = dotProduct(params.query_vector, 'embedding'); return score > 0 ? score : 0.0;",
                    params
            );

            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                    .query(QueryBuilders.scriptScoreQuery(
                            QueryBuilders.matchAllQuery(),
                            script
                    ))
                    .size(topK);

            SearchRequest searchRequest = new SearchRequest(INDEX_NAME)
                    .source(sourceBuilder);

            log.debug("Sending vector search request to Elasticsearch...");
            SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);

            List<RetrievedChunk> results = parseSearchResponse(searchResponse);
            log.info("[VECTOR SEARCH] Completed: {} results found", results.size());

            return results;

        } catch (Exception e) {
            log.error("[VECTOR SEARCH] FAILED for query '{}': {}", queryText, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Performs a traditional keyword-based search with field boosting.
     */
    public List<RetrievedChunk> searchByText(String queryText, int topK) {
        try {
            log.info("[TEXT SEARCH] Starting for: '{}' (topK: {})", queryText, topK);

            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                    .query(QueryBuilders.multiMatchQuery(queryText)
                            .field("text")
                            .field("keywords", 3.0f)
                            .field("heading", 2.0f)
                            .field("summary")
                            .lenient(true)
                    )
                    .size(topK);

            SearchRequest searchRequest = new SearchRequest(INDEX_NAME).source(sourceBuilder);

            log.debug("Sending text search request to Elasticsearch...");
            SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

            List<RetrievedChunk> results = parseSearchResponse(response);
            log.info("[TEXT SEARCH] Completed: {} results found", results.size());

            return results;

        } catch (Exception e) {
            log.error("[TEXT SEARCH] FAILED: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Executes both Vector and Text searches asynchronously and merges the results
     * using Reciprocal Rank Fusion (RRF).
     */
    public List<RetrievedChunk> hybridSearch(String queryText, float[] queryEmbedding, int topK) {
        log.info("[HYBRID SEARCH] Starting with topK={}", topK);

        // Execute both queries concurrently to minimize latency
        CompletableFuture<List<RetrievedChunk>> vectorFuture = CompletableFuture.supplyAsync(() ->
                searchSimilar(queryText, queryEmbedding, topK * 2)
        );

        CompletableFuture<List<RetrievedChunk>> textFuture = CompletableFuture.supplyAsync(() ->
                searchByText(queryText, topK * 2)
        );

        // Await completion of both asynchronous tasks
        CompletableFuture.allOf(vectorFuture, textFuture).join();

        List<RetrievedChunk> vectorResults = vectorFuture.join();
        List<RetrievedChunk> textResults = textFuture.join();

        // Apply Reciprocal Rank Fusion (RRF) for optimal result merging
        log.info("Merging results using RRF ranking strategy...");
        Map<Long, RetrievedChunk> merged = new HashMap<>();
        Map<Long, Double> rrfScores = new HashMap<>();
        final int rrfConstantK = 60;

        for (int i = 0; i < vectorResults.size(); i++) {
            RetrievedChunk chunk = vectorResults.get(i);
            merged.put(chunk.getChunkId(), chunk);
            rrfScores.put(chunk.getChunkId(), 1.0 / (rrfConstantK + i + 1));
        }

        for (int i = 0; i < textResults.size(); i++) {
            RetrievedChunk chunk = textResults.get(i);
            merged.putIfAbsent(chunk.getChunkId(), chunk);
            double currentScore = rrfScores.getOrDefault(chunk.getChunkId(), 0.0);
            rrfScores.put(chunk.getChunkId(), currentScore + (1.0 / (rrfConstantK + i + 1)));
        }

        List<RetrievedChunk> finalResults = merged.values().stream()
                .peek(chunk -> chunk.setScore(rrfScores.get(chunk.getChunkId())))
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .toList();

        log.info("[HYBRID SEARCH] Completed: {} merged results (from {} unique chunks)",
                finalResults.size(), merged.size());

        return finalResults;
    }

    private List<Float> toList(float[] embedding) {
        List<Float> list = new ArrayList<>(embedding.length);
        for (float v : embedding) list.add(v);
        return list;
    }

    private List<RetrievedChunk> parseSearchResponse(SearchResponse searchResponse) {
        if (searchResponse.getHits() == null) {
            log.warn("Search response has null hits");
            return Collections.emptyList();
        }

        List<RetrievedChunk> results = Arrays.stream(searchResponse.getHits().getHits())
                .map(hit -> {
                    Map<String, Object> source = hit.getSourceAsMap();

                    Float importance = null;
                    Object importanceObj = source.get("importance");
                    if (importanceObj instanceof Number) {
                        importance = ((Number) importanceObj).floatValue();
                    }

                    return RetrievedChunk.builder()
                            .chunkId(Long.valueOf(hit.getId()))
                            .text((String) source.getOrDefault("text", ""))
                            .summary((String) source.get("summary"))
                            .heading((String) source.get("heading"))
                            .sourceUrl((String) source.getOrDefault("sourceUrl", ""))
                            .documentTitle((String) source.getOrDefault("documentTitle", ""))
                            .score(hit.getScore())
                            .importance(importance)
                            .build();
                })
                .collect(Collectors.toList());

        log.debug("Parsed {} chunks from search response", results.size());
        return results;
    }
}