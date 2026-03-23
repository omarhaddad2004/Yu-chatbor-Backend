package jo.edu.yu.yu_chatbot.rag;

import jo.edu.yu.yu_chatbot.document.ChunkEntity;
import jo.edu.yu.yu_chatbot.document.ChunkRepository;
import jo.edu.yu.yu_chatbot.embedding.EmbeddingClient;
import jo.edu.yu.yu_chatbot.embedding.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service that orchestrates the end-to-end intelligent retrieval process.
 * Integrates caching, heuristic routing for exact name searches, hybrid search,
 * and contextual chunk stitching (windowing) with score preservation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmartRetrievalService {

    private final VectorStoreService vectorStoreService;
    private final ChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final ScopeGate scopeGate;
    private final LlmClient llmClient;

    private static final int INITIAL_TOP_K = 12;
    private static final int BROAD_TOP_K = 40;
    private static final int WINDOW_SIZE = 1;
    private static final int MAX_GAP = 2;

    /**
     * Executes the smart retrieval pipeline with caching enabled.
     * Routes queries to either strict text search (for names) or hybrid vector search,
     * then applies filtering and contextual windowing.
     */
    @Cacheable(
            value = "smartSearchResults",
            key = "#question.toLowerCase().trim()"
    )
    @Transactional(readOnly = true)
    public List<RetrievedChunk> smartSearch(String question) {
        try {
            log.info("[SMART SEARCH] Starting retrieval pipeline for query: '{}'", question);

            String searchQuery = question;
            List<RetrievedChunk> rawHits;
            int dynamicTopK = INITIAL_TOP_K;

            // 1. Heuristic Check for Specific Entity/Name Queries (Arabic optimization)
            boolean containsArabic = question != null &&
                    question.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06FF);

            boolean looksLikePersonQuery = containsArabic &&
                    (question.contains("د.") ||
                            question.contains("دكتور") ||
                            question.contains("الدكتور") ||
                            (question.length() < 50 && question.contains("اسم")));

            if (looksLikePersonQuery) {
                // Route to Text Search for precise name matching
                log.info("Query identified as a specific person/name search. Routing to TEXT search.");
                rawHits = vectorStoreService.searchByText(searchQuery, dynamicTopK);

                if (rawHits.isEmpty()) {
                    log.warn("Text search yielded no results for name query. Proceeding to fallback logic.");
                }
            } else {
                rawHits = Collections.emptyList(); // Initialize empty to trigger standard flow
            }

            // 2. Standard Flow: Translation, Embedding, and Hybrid Search (if Text Search didn't resolve)
            if (rawHits.isEmpty()) {
                if (question != null && question.matches(".*[a-zA-Z]+.*")) {
                    log.info("English characters detected in query, attempting translation...");
                    try {
                        String translationPrompt = "Translate to Arabic. Output ONLY Arabic text: " + question;
                        searchQuery = llmClient.generateAnswer(translationPrompt).trim();
                        log.info("Query successfully translated to Arabic: '{}'", searchQuery);
                    } catch (Exception e) {
                        log.warn("Translation service failed, falling back to original query. Error: {}", e.getMessage());
                        searchQuery = question;
                    }
                }

                log.info("Requesting vector embedding from external client...");
                float[] embedding = embeddingClient.embed(searchQuery);

                if (embedding == null || embedding.length == 0) {
                    log.error("Received null or empty embedding array from client. Aborting search.");
                    return List.of();
                }

                String lowerQ = searchQuery.toLowerCase();
                if (lowerQ.contains("جميع") || lowerQ.contains("كل") ||
                        lowerQ.contains("list") || lowerQ.contains("all") ||
                        lowerQ.contains("دكاترة") || lowerQ.contains("مدرسين") ||
                        lowerQ.contains("أسماء") || lowerQ.contains("اعضاء") ||
                        lowerQ.contains("عميد")) {
                    dynamicTopK = BROAD_TOP_K;
                    log.info("Broad query intent detected. Adjusting topK parameter to {}", dynamicTopK);
                }

                log.info("Executing Elasticsearch hybrid search with topK={}...", dynamicTopK);
                rawHits = vectorStoreService.hybridSearch(searchQuery, embedding, dynamicTopK);
            }

            log.info("Initial retrieval phase returned {} raw document chunks.", rawHits.size());

            if (rawHits.isEmpty()) {
                log.warn("Retrieval engines returned zero results for the given query.");
                return List.of();
            }

            // 3. Apply Domain Filtering
            log.info("Applying domain-specific filters via ScopeGate...");
            List<RetrievedChunk> filteredHits = scopeGate.filter(rawHits);
            log.info("Filtering complete. Retained {} chunks (Filtered out {} chunks).",
                    filteredHits.size(),
                    rawHits.size() - filteredHits.size());

            if (filteredHits.isEmpty()) {
                log.warn("All retrieved chunks were discarded by ScopeGate filters.");
                return List.of();
            }

            // Map to preserve original scores for ranking the final merged blocks
            Map<Long, Double> hitScoresMap = filteredHits.stream()
                    .collect(Collectors.toMap(RetrievedChunk::getChunkId, RetrievedChunk::getScore));

            // 4. Contextual Stitching (Chunk Windowing)
            log.info("Initializing contextual chunk stitching (Grouping by Document ID)...");
            Map<Long, List<Integer>> docHitsMap = new HashMap<>();
            Map<Long, String> docUrlMap = new HashMap<>();
            Map<Long, String> docTitleMap = new HashMap<>();

            List<Long> hitIds = new ArrayList<>(hitScoresMap.keySet());
            List<ChunkEntity> hitEntities = chunkRepository.findAllById(hitIds);

            for (ChunkEntity entity : hitEntities) {
                if (entity.getDocument() != null) {
                    Long docId = entity.getDocument().getId();
                    docHitsMap.computeIfAbsent(docId, k -> new ArrayList<>()).add(entity.getChunkIndex());
                    docUrlMap.putIfAbsent(docId, entity.getDocument().getSourceUrl());
                    docTitleMap.putIfAbsent(docId, entity.getDocument().getTitle());
                }
            }

            // 5. Merge and Enrich Context with Score Preservation
            log.info("Merging adjacent chunks to build coherent context blocks...");
            List<RetrievedChunk> enrichedChunks = new ArrayList<>();

            for (Map.Entry<Long, List<Integer>> entry : docHitsMap.entrySet()) {
                Long docId = entry.getKey();
                List<Integer> indices = entry.getValue();
                Collections.sort(indices);

                List<Range> mergedRanges = calculateMergedRanges(indices);

                for (Range range : mergedRanges) {
                    List<ChunkEntity> stitchedChunks = chunkRepository.findChunksInWindow(
                            docId, range.start(), range.end()
                    );

                    if (stitchedChunks.isEmpty()) {
                        continue;
                    }

                    StringBuilder fullTextBlock = new StringBuilder();
                    String firstHeading = null;
                    String firstSummary = null;
                    double maxScoreForWindow = 0.0;

                    for (ChunkEntity c : stitchedChunks) {
                        fullTextBlock.append(c.getText()).append("\n");

                        if (firstHeading == null && c.getHeading() != null) {
                            firstHeading = c.getHeading();
                        }

                        if (firstSummary == null && c.getSummary() != null) {
                            firstSummary = c.getSummary();
                        }

                        Double chunkScore = hitScoresMap.get(c.getId());
                        if (chunkScore != null && chunkScore > maxScoreForWindow) {
                            maxScoreForWindow = chunkScore;
                        }
                    }

                    enrichedChunks.add(RetrievedChunk.builder()
                            .chunkId(0L)
                            .text(fullTextBlock.toString().trim())
                            .summary(firstSummary)
                            .heading(firstHeading)
                            .sourceUrl(docUrlMap.get(docId))
                            .documentTitle(docTitleMap.get(docId))
                            .score(maxScoreForWindow)
                            .build());
                }
            }

            // 6. Final Sorting based on the preserved scores
            log.info("Sorting final enriched chunks by calculated relevance score.");
            enrichedChunks.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

            log.info("[SMART SEARCH COMPLETE] Pipeline finished successfully. Returning {} enriched context blocks.",
                    enrichedChunks.size());
            return enrichedChunks;

        } catch (Exception e) {
            log.error("[SMART SEARCH FAILED] An unexpected error occurred during the retrieval pipeline.", e);
            return List.of();
        }
    }

    private List<Range> calculateMergedRanges(List<Integer> sortedIndices) {
        if (sortedIndices.isEmpty()) return Collections.emptyList();

        List<Range> ranges = new ArrayList<>();
        int currentStart = Math.max(0, sortedIndices.get(0) - WINDOW_SIZE);
        int currentEnd = sortedIndices.get(0) + WINDOW_SIZE;

        for (int i = 1; i < sortedIndices.size(); i++) {
            int nextIndex = sortedIndices.get(i);
            int nextStart = Math.max(0, nextIndex - WINDOW_SIZE);
            int nextEnd = nextIndex + WINDOW_SIZE;

            if (nextStart <= currentEnd + MAX_GAP) {
                currentEnd = Math.max(currentEnd, nextEnd);
            } else {
                ranges.add(new Range(currentStart, currentEnd));
                currentStart = nextStart;
                currentEnd = nextEnd;
            }
        }
        ranges.add(new Range(currentStart, currentEnd));

        return ranges;
    }

    private record Range(int start, int end) {}
}