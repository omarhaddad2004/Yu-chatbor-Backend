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

    @Cacheable(value = "smartSearchResults", key = "#question.toLowerCase().trim()")
    @Transactional(readOnly = true)
    public List<RetrievedChunk> smartSearch(String question) {
        try {
            log.info("[SMART SEARCH] Processing: '{}'", question);

            String searchQuery = question;

            // 1. Translation for English queries
            if (question.matches(".*[a-zA-Z]+.*")) {
                try {
                    String translationPrompt = "Translate to Arabic. Output ONLY Arabic text: " + question;
                    searchQuery = llmClient.generateAnswer(translationPrompt).trim();
                } catch (Exception e) {
                    log.warn("Translation failed: {}", e.getMessage());
                    searchQuery = question;
                }
            }

            // 2. Get embedding
            float[] embedding = embeddingClient.embed(searchQuery);
            if (embedding == null || embedding.length == 0) return List.of();

            // 3. Dynamic topK
            int dynamicTopK = INITIAL_TOP_K;
            String lowerQ = searchQuery.toLowerCase();
            if (lowerQ.contains("جميع") || lowerQ.contains("كل") ||
                    lowerQ.contains("list") || lowerQ.contains("all") ||
                    lowerQ.contains("دكاترة") || lowerQ.contains("مدرسين") ||
                    lowerQ.contains("أسماء") || lowerQ.contains("اعضاء") ||
                    lowerQ.contains("عميد")) {
                dynamicTopK = BROAD_TOP_K;
            }

            // 4. Hybrid Search ONLY
            List<RetrievedChunk> rawHits = vectorStoreService.hybridSearch(searchQuery, embedding, dynamicTopK);
            if (rawHits.isEmpty()) return List.of();

            // 5. Apply filters
            List<RetrievedChunk> filteredHits = scopeGate.filter(rawHits);
            if (filteredHits.isEmpty()) return List.of();

            Map<Long, Double> hitScoresMap = filteredHits.stream()
                    .collect(Collectors.toMap(RetrievedChunk::getChunkId, RetrievedChunk::getScore, Math::max));

            // 6. IN-MEMORY WINDOWING (تجميع الجدول كما كان يحدث قديماً ولكن بسرعة الـ RAM)
            Map<Long, List<Integer>> docHitsMap = new HashMap<>();
            Map<Long, String> docUrlMap = new HashMap<>();
            Map<Long, String> docTitleMap = new HashMap<>();
            List<Long> targetDocIds = new ArrayList<>();

            List<ChunkEntity> hitEntities = chunkRepository.findAllById(new ArrayList<>(hitScoresMap.keySet()));
            for (ChunkEntity entity : hitEntities) {
                Long docId = entity.getDocument().getId();
                docHitsMap.computeIfAbsent(docId, k -> new ArrayList<>()).add(entity.getChunkIndex());
                docUrlMap.putIfAbsent(docId, entity.getDocument().getSourceUrl());
                docTitleMap.putIfAbsent(docId, entity.getDocument().getTitle());
                if (!targetDocIds.contains(docId)) targetDocIds.add(docId);
            }

            List<ChunkEntity> allTargetChunks = chunkRepository.findByDocumentIdIn(targetDocIds);
            Map<Long, Map<Integer, ChunkEntity>> ramChunkCache = new HashMap<>();
            for (ChunkEntity c : allTargetChunks) {
                ramChunkCache.computeIfAbsent(c.getDocument().getId(), k -> new HashMap<>()).put(c.getChunkIndex(), c);
            }

            List<RetrievedChunk> enrichedChunks = new ArrayList<>();
            for (Map.Entry<Long, List<Integer>> entry : docHitsMap.entrySet()) {
                Long docId = entry.getKey();
                List<Integer> indices = entry.getValue();
                Collections.sort(indices);

                Map<Integer, ChunkEntity> docChunksInRam = ramChunkCache.getOrDefault(docId, Collections.emptyMap());
                List<Range> mergedRanges = calculateMergedRanges(indices);

                for (Range range : mergedRanges) {
                    StringBuilder sb = new StringBuilder();
                    double maxScore = 0.0;
                    String firstHeading = null;
                    String firstSummary = null;

                    for (int i = range.start(); i <= range.end(); i++) {
                        ChunkEntity c = docChunksInRam.get(i);
                        if (c != null) {
                            sb.append(c.getText()).append("\n");
                            maxScore = Math.max(maxScore, hitScoresMap.getOrDefault(c.getId(), 0.0));
                            if (firstHeading == null && c.getHeading() != null) firstHeading = c.getHeading();
                            if (firstSummary == null && c.getSummary() != null) firstSummary = c.getSummary();
                        }
                    }

                    if (sb.length() > 0) {
                        enrichedChunks.add(RetrievedChunk.builder()
                                .text(sb.toString().trim())
                                .summary(firstSummary)
                                .heading(firstHeading)
                                .sourceUrl(docUrlMap.get(docId))
                                .documentTitle(docTitleMap.get(docId))
                                .score(maxScore)
                                .build());
                    }
                }
            }

            // 7. Sort and Return 
            enrichedChunks.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            return enrichedChunks;

        } catch (Exception e) {
            log.error("Smart Search Error: ", e);
            return List.of();
        }
    }

    private List<Range> calculateMergedRanges(List<Integer> sortedIndices) {
        if (sortedIndices.isEmpty()) return Collections.emptyList();
        List<Range> ranges = new ArrayList<>();
        int start = Math.max(0, sortedIndices.get(0) - WINDOW_SIZE);
        int end = sortedIndices.get(0) + WINDOW_SIZE;

        for (int i = 1; i < sortedIndices.size(); i++) {
            if (sortedIndices.get(i) - WINDOW_SIZE <= end + MAX_GAP) {
                end = Math.max(end, sortedIndices.get(i) + WINDOW_SIZE);
            } else {
                ranges.add(new Range(start, end));
                start = Math.max(0, sortedIndices.get(i) - WINDOW_SIZE);
                end = sortedIndices.get(i) + WINDOW_SIZE;
            }
        }
        ranges.add(new Range(start, end));
        return ranges;
    }

    private record Range(int start, int end) {}
}
