# YU-Bot: Enterprise-Grade RAG Backend for Academic Advising

## Overview
YU-Bot is an advanced, highly optimized Retrieval-Augmented Generation (RAG) backend system designed to act as an intelligent Academic Advisor for Yarmouk University. Built with a focus on high availability, low latency, and zero-hallucination responses, this system orchestrates complex data crawling, semantic search, and reactive LLM streaming.

Unlike standard API wrappers, this project implements a custom-built RAG engine from scratch, solving critical AI integration challenges such as LLM context fragmentation, API rate limiting, and exact-match entity retrieval.

## Key Architectural Achievements

### 1. Advanced Hybrid Search with Reciprocal Rank Fusion (RRF)
The system does not rely on vector search alone. It implements a concurrent Hybrid Search pipeline executing both Semantic Vector Search (via Cosine Similarity) and Lexical Text Search (via BM25) simultaneously. The results are mathematically merged using Reciprocal Rank Fusion (RRF) to ensure optimal retrieval accuracy, bridging the gap between contextual meaning and exact keyword matching.

### 2. Contextual Windowing & Score Preservation
To prevent the LLM from losing the broader context of a retrieved chunk, the `SmartRetrievalService` implements a highly efficient "Chunk Stitching" algorithm. It dynamically calculates adjacent text chunks from the database (Windowing) and merges them into coherent blocks. Crucially, it preserves the highest RRF score from Elasticsearch for the merged block, ensuring the LLM reads the most relevant, fully contextualized data first.

### 3. True Round-Robin LLM Load Balancing
To combat HTTP 429 (Too Many Requests) errors and API quota exhaustion, the system features a custom, atomic Round-Robin load balancer for Gemini API keys. Built using Spring WebFlux, it dynamically rotates keys on every request and supports seamless failover and retries during reactive SSE (Server-Sent Events) streaming.

### 4. Intelligent Data Ingestion & Heuristic Routing
* **Web Crawler:** A multi-threaded, depth-aware crawler utilizing Jsoup. It features "Deep Dive" capabilities to immediately merge structured faculty cards with comprehensive external profile data.
* **Complex PDF Parsing:** Integrates Tabula and Apache PDFBox to accurately extract tabular data (like academic study plans) and convert them into LLM-digestible Markdown.
* **Heuristic Query Routing:** Automatically detects Arabic entity queries (e.g., specific doctor names) and routes them directly to precise Text Search engines to prevent vector-space dilution.

### 5. Database & Performance Optimization
* Eliminated the infamous "N+1 Query Problem" during the context enrichment phase by utilizing optimized Bulk Fetching (`findAllById`).
* In-memory caching mechanisms using Spring `@Cacheable` to instantly serve repeated complex queries without re-triggering the embedding or retrieval pipelines.

## Technology Stack

* **Core Framework:** Java 17, Spring Boot 3, Spring WebFlux (Reactive Programming), Spring Data JPA
* **Security:** Spring Security, Stateless JWT Authentication
* **AI / LLM:** Gemini 1.5/2.5 Flash (Reactive Streaming), BAAI/bge-m3 (Embeddings)
* **Search Engine:** Elasticsearch 7.10 (Vector & Text Hybrid Search)
* **Database:** PostgreSQL with pgvector extension, Flyway for database migrations
* **Document Processing:** Apache Tika, PDFBox, Tabula (Table Extraction), Jsoup
* **Infrastructure:** Docker & Docker Compose

## Quick Start (Docker)

Ensure Docker and Docker Compose are installed on your machine.

1. Clone the repository.
2. Navigate to the project root directory.
3. Start the infrastructure (PostgreSQL and Elasticsearch):
   `docker-compose up -d`
4. Run the Spring Boot application.

*Note: Ensure environment variables for API keys (e.g., GEMINI_API_KEY) are properly set in your deployment environment before running.*
