package jo.edu.yu.yu_chatbot.crawler;

import jo.edu.yu.yu_chatbot.cleaning.TextCleaningService;
import jo.edu.yu.yu_chatbot.document.*;
import jo.edu.yu.yu_chatbot.embedding.EmbeddingClient;
import jo.edu.yu.yu_chatbot.rag.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.net.URL;
import java.time.Instant;
import java.util.*;

/**
 * Core component responsible for crawling university websites, parsing content,
 * generating intelligent chunks, and coordinating the batch embedding process.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebsiteCrawlerRunner {

    private final WebsiteCrawler websiteCrawler;
    private final TextCleaningService textCleaningService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final TextChunker textChunker;
    private final EmbeddingClient embeddingClient;
    private final VectorStoreService vectorStoreService;

    @Value("${crawler.base-domain:yu.edu.jo}")
    private String baseDomain;

    @Value("${crawler.max-pages-per-root:3000}")
    private int maxPagesPerRoot;

    @Value("${crawler.max-depth:6}")
    private int maxDepth;

    private final List<ChunkEntity> globalChunkBuffer = new ArrayList<>();
    private static final int GLOBAL_BATCH_SIZE = 10;

    public void ingestUrls(List<String> roots) {
        if (roots == null || roots.isEmpty()) return;

        for (String rootUrl : roots) {
            if (rootUrl == null || rootUrl.trim().isEmpty()) continue;
            String url = rootUrl.trim();

            log.info("[STARTING NEW ROOT] {}", url);

            if (isPdfUrl(url)) {
                crawlSinglePage(url);
            } else {
                crawlRootStrictly(url);
            }

            flushGlobalBuffer();
            log.info("[FINISHED ROOT] {}", url);
        }
    }

    private boolean isPdfUrl(String url) {
        if (url == null) return false;
        return url.replaceAll("/+$", "").toLowerCase().endsWith(".pdf");
    }

    private void crawlSinglePage(String url) {
        if (url == null || !url.contains(baseDomain)) return;
        String normalized = normalizeUrl(url);

        if (documentRepository.existsBySourceUrl(normalized)) {
            log.info("Already exists in DB: {}", normalized);
            return;
        }

        try {
            CrawledPage page = websiteCrawler.crawl(normalized);
            if (page != null && page.getContent() != null && !page.getContent().isBlank()) {
                processPage(page);
            }
        } catch (Exception e) {
            log.error("Single page crawl failed: {}", url, e);
        }
    }

    private void crawlRootStrictly(String startUrl) {
        if (startUrl == null) return;
        String allowedSubdomain = extractFullDomain(startUrl);
        if (allowedSubdomain == null) return;

        Set<String> visited = new HashSet<>();
        Deque<UrlWithDepth> queue = new ArrayDeque<>();
        queue.add(new UrlWithDepth(normalizeUrl(startUrl), 0));
        int pagesProcessed = 0;

        log.info("Starting crawl for subdomain: {}", allowedSubdomain);
        log.info("Crawl Limits - maxPages: {}, maxDepth: {}", maxPagesPerRoot, maxDepth);

        while (!queue.isEmpty() && pagesProcessed < maxPagesPerRoot) {
            UrlWithDepth current = queue.poll();
            String url = current.url;
            int depth = current.depth;

            if (url == null || visited.contains(url)) continue;
            if (depth > maxDepth) continue;

            if (!belongsToSameDomain(url, allowedSubdomain)) continue;

            String normalized = normalizeUrl(url);

            if (documentRepository.existsBySourceUrl(normalized)) {
                visited.add(normalized);
                log.debug("Already in DB, skipping: {}", normalized);
                continue;
            }

            visited.add(normalized);

            try { Thread.sleep(200); } catch (InterruptedException ignored) {}

            log.info("[{}/{}] Depth[{}]: Processing {}", (pagesProcessed + 1), maxPagesPerRoot, depth, normalized);

            try {
                CrawledPage page = websiteCrawler.crawl(normalized);

                if (page != null && page.getContent() != null && !page.getContent().isBlank()) {
                    processPage(page);
                    pagesProcessed++;

                    if (page.getLinks() != null && depth < maxDepth) {
                        int newLinksAdded = 0;
                        for (String link : page.getLinks()) {
                            String normalizedLink = normalizeUrl(link);
                            if (normalizedLink != null &&
                                    belongsToSameDomain(normalizedLink, allowedSubdomain) &&
                                    !visited.contains(normalizedLink)) {
                                queue.add(new UrlWithDepth(normalizedLink, depth + 1));
                                newLinksAdded++;
                            }
                        }
                        log.debug("Added {} new links to queue (Current queue size: {})", newLinksAdded, queue.size());
                    }
                } else {
                    log.warn("Empty content extracted from: {}", normalized);
                }

            } catch (Exception e) {
                log.error("Error processing URL {}: {}", normalized, e.getMessage());
            }
        }

        log.info("Crawl completed. Total pages processed: {}", pagesProcessed);
    }

    private void processPage(CrawledPage page) {
        try {
            log.debug("Processing page content: {}", page.getUrl());

            String normalized = normalizeUrl(page.getUrl());

            if (documentRepository.existsBySourceUrl(normalized)) {
                log.info("Page already processed, skipping: {}", normalized);
                return;
            }

            String cleaned = textCleaningService.basicClean(page.getContent());

            if (cleaned.length() < 50) {
                log.warn("Content too short ({} chars), skipping: {}", cleaned.length(), page.getUrl());
                return;
            }

            DocumentEntity document = DocumentEntity.builder()
                    .sourceUrl(normalized)
                    .title(page.getTitle())
                    .rawContent(page.getContent())
                    .cleanedContent(cleaned)
                    .contentType(page.getContentType())
                    .createdAt(Instant.now())
                    .build();

            document = documentRepository.save(document);
            log.debug("Saved document to DB with ID: {}", document.getId());

            if ("FACULTY-MEMBER".equals(page.getContentType())) {
                processFacultyPage(document, cleaned);
            } else {
                processNormalPage(document, cleaned, normalized);
            }

        } catch (Exception e) {
            log.error("Process error for URL {}: {}", page.getUrl(), e.getMessage(), e);
        }
    }

    private void processFacultyPage(DocumentEntity document, String content) {
        log.info("Processing faculty member page as a single chunk to preserve context.");

        String summary = createSummary(content);
        String heading = detectHeading(content);

        ChunkEntity chunk = ChunkEntity.builder()
                .document(document)
                .chunkIndex(0)
                .text(content)
                .summary(summary)
                .heading(heading)
                .keywords("")
                .importance(1.0f)
                .build();

        chunk = chunkRepository.save(chunk);
        addToGlobalBuffer(chunk);

        log.info("Faculty page successfully stored as a single chunk.");
    }

    private void processNormalPage(DocumentEntity document, String content, String url) {
        List<String> chunksText = textChunker.chunk(content);
        log.debug("Split page into {} chunks.", chunksText.size());

        int index = 0;
        int validChunks = 0;

        for (String text : chunksText) {
            if (text == null || text.isBlank()) {
                index++;
                continue;
            }

            if (isGarbageChunk(text, url)) {
                log.debug("Skipping identified garbage chunk at index {}", index);
                index++;
                continue;
            }

            String summary = createSummary(text);
            String heading = detectHeading(text);
            float importance = calculateImportance(text, index, document.getContentType());

            ChunkEntity chunk = ChunkEntity.builder()
                    .document(document)
                    .chunkIndex(index++)
                    .text(text)
                    .summary(summary)
                    .heading(heading)
                    .keywords("")
                    .importance(importance)
                    .build();

            chunk = chunkRepository.save(chunk);
            addToGlobalBuffer(chunk);
            validChunks++;
        }

        log.info("Normal page processing complete: {} valid chunks created.", validChunks);
    }

    private boolean isGarbageChunk(String text, String url) {
        if (url != null && url.toLowerCase().contains("fmd.yu.edu.jo")) {
            return false;
        }

        if (text.contains("@") || text.matches(".*\\d{4,}.*")) {
            return false;
        }

        if (text.contains("|") && text.contains("---")) {
            return false;
        }

        if (text.length() < 30) return true;

        String noSpecialChars = text.replaceAll("[|\\-\\s]+", "");
        return noSpecialChars.length() < (text.length() * 0.25);
    }

    private String normalizeUrl(String url) {
        if (url == null) return null;
        url = url.trim().replaceAll("/+$", "").replaceAll("#.*$", "");
        return url.replaceAll("/(index\\.(html?|php))$", "");
    }

    private String createSummary(String text) {
        if (text == null || text.isEmpty()) return "";

        String cleaned = text.replaceAll("\\s+", " ").trim();
        int length = Math.min(cleaned.length(), 300);
        String summary = cleaned.substring(0, length);

        int lastPeriod = summary.lastIndexOf('.');
        int lastQuestion = summary.lastIndexOf('؟');
        int lastExclamation = summary.lastIndexOf('!');

        int lastSentenceEnd = Math.max(lastPeriod, Math.max(lastQuestion, lastExclamation));

        if (lastSentenceEnd > 100) {
            summary = summary.substring(0, lastSentenceEnd + 1);
        }

        return summary.trim();
    }

    private String detectHeading(String text) {
        if (text == null || text.isBlank()) return null;

        String[] lines = text.split("\n", 5);

        for (String line : lines) {
            String trimmed = line.trim().replaceAll("^#+\\s*", "");

            if (trimmed.length() >= 5 && trimmed.length() <= 120) {
                if (!trimmed.contains("|") && !trimmed.matches("^[\\d\\s.،-]+$")) {
                    long letterCount = trimmed.chars().filter(Character::isLetter).count();
                    if (letterCount > trimmed.length() * 0.5) {
                        return trimmed;
                    }
                }
            }
        }
        return null;
    }

    private float calculateImportance(String text, int chunkIndex, String contentType) {
        float score = 0.5f;

        if (chunkIndex == 0) {
            score += 0.3f;
        } else if (chunkIndex < 3) {
            score += 0.2f;
        } else if (chunkIndex < 5) {
            score += 0.1f;
        }

        if (contentType != null) {
            if (contentType.contains("FACULTY-MEMBER")) {
                score += 0.3f;
            } else if (contentType.contains("PDF")) {
                score += 0.15f;
            }
        }

        String lower = text.toLowerCase();

        if (lower.contains("خطة") || lower.contains("plan")) score += 0.15f;
        if (lower.contains("متطلبات") || lower.contains("requirements")) score += 0.15f;
        if (lower.contains("ساعات") || lower.contains("credit")) score += 0.1f;
        if (lower.contains("عميد") || lower.contains("dean")) score += 0.2f;
        if (lower.contains("رئيس قسم") || lower.contains("head of department")) score += 0.2f;

        if (lower.contains("@") || lower.contains("email") || lower.contains("إيميل") ||
                lower.contains("phone") || lower.contains("هاتف")) {
            score += 0.2f;
        }

        if (lower.contains("office") || lower.contains("مكتب") ||
                lower.contains("room") || lower.contains("غرفة")) {
            score += 0.15f;
        }

        if (text.matches(".*\\d{3,}.*")) score += 0.05f;

        int length = text.length();
        if (length > 500 && length < 2000) {
            score += 0.1f;
        } else if (length < 200) {
            score -= 0.05f;
        }

        if (text.contains("|") && text.contains("---")) {
            score += 0.2f;
        }

        return Math.min(1.0f, score);
    }

    private void addToGlobalBuffer(ChunkEntity chunk) {
        globalChunkBuffer.add(chunk);

        if (globalChunkBuffer.size() >= GLOBAL_BATCH_SIZE) {
            flushGlobalBuffer();
        }
    }

    private void flushGlobalBuffer() {
        if (globalChunkBuffer.isEmpty()) return;

        log.info("[EMBEDDING] Initiating batch embedding for {} chunks...", globalChunkBuffer.size());

        try {
            List<String> texts = globalChunkBuffer.stream()
                    .map(ChunkEntity::getText)
                    .toList();

            long startTime = System.currentTimeMillis();
            List<float[]> embeddings = embeddingClient.batchEmbed(texts);
            long duration = System.currentTimeMillis() - startTime;

            log.debug("Embedding batch completed in {}ms (avg {} ms/chunk)",
                    duration, duration / texts.size());

            if (embeddings.size() != globalChunkBuffer.size()) {
                log.error("Embedding mismatch! Expected {}, received {}",
                        globalChunkBuffer.size(), embeddings.size());
                return;
            }

            int successCount = 0;
            for (int i = 0; i < globalChunkBuffer.size(); i++) {
                ChunkEntity chunk = globalChunkBuffer.get(i);
                float[] embedding = embeddings.get(i);

                if (embedding != null && embedding.length > 0) {
                    vectorStoreService.upsertEmbedding(chunk.getId(), embedding);
                    successCount++;
                } else {
                    log.warn("Empty embedding array returned for chunk ID {}", chunk.getId());
                }
            }

            log.info("Successfully indexed {}/{} chunks into Elasticsearch.", successCount, globalChunkBuffer.size());

        } catch (Exception e) {
            log.error("Error occurred during buffer flush: {}", e.getMessage(), e);
        } finally {
            globalChunkBuffer.clear();
        }
    }

    private boolean belongsToSameDomain(String urlString, String allowedDomain) {
        if (urlString == null || allowedDomain == null) return false;

        try {
            String host = new URL(urlString).getHost().toLowerCase();

            if (host.equals(allowedDomain)) {
                return true;
            }

            if (host.equals("fmd.yu.edu.jo")) {
                return true;
            }

            return false;

        } catch (Exception e) {
            log.debug("Invalid URL format encountered: {}", urlString);
            return false;
        }
    }

    private String extractFullDomain(String urlString) {
        try {
            return new URL(urlString).getHost().toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Application shutdown initiated: Flushing remaining chunks from buffer...");
        flushGlobalBuffer();
        log.info("Shutdown sequence complete.");
    }

    private static class UrlWithDepth {
        String url;
        int depth;

        UrlWithDepth(String url, int depth) {
            this.url = url;
            this.depth = depth;
        }
    }
}