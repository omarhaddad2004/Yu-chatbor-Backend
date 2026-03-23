package jo.edu.yu.yu_chatbot.rag;

import jo.edu.yu.yu_chatbot.chat.Message;
import jo.edu.yu.yu_chatbot.chat.SenderType;
import jo.edu.yu.yu_chatbot.embedding.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.List;

/**
 * Core service coordinating the Retrieval-Augmented Generation (RAG) flow.
 * Manages prompt construction, conversation history injection, and LLM interaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final SmartRetrievalService smartRetrievalService;
    private final LlmClient llmClient;

    public RagResult answerWithHistory(String question, List<Message> history) {
        log.info("AI Agent receiving question: '{}' with history size: {}", question, history.size());

        List<RetrievedChunk> smartContext = smartRetrievalService.smartSearch(question);

        String prompt = buildAgentPromptWithHistory(question, smartContext, history);
        String answer = llmClient.generateAnswer(prompt);

        return RagResult.builder()
                .answer(answer)
                .sources(smartContext)
                .build();
    }

    public Flux<RagResult> streamAnswerWithHistory(String question, List<Message> history) {
        log.info("Streaming AI Answer for question: '{}' with history size: {}", question, history.size());

        List<RetrievedChunk> smartContext = smartRetrievalService.smartSearch(question);
        String prompt = buildAgentPromptWithHistory(question, smartContext, history);

        return llmClient.streamAnswer(prompt)
                .map(chunkText -> RagResult.builder()
                        .answer(chunkText)
                        .sources(smartContext)
                        .build());
    }

    private String buildAgentPromptWithHistory(String question, List<RetrievedChunk> chunks, List<Message> history) {
        int currentYear = LocalDate.now().getYear();
        StringBuilder sb = new StringBuilder();

        sb.append(String.format("""
                You are **YU-Bot**, the official, highly professional, and intelligent Academic Advisor AI for **Yarmouk University (YU)**.
                Current Year: %d.

                ### ️ YOUR PERSONA & TONE:
                - **Professional & Confident:** Maintain an academic, objective, and confident tone. Speak like a knowledgeable university official.
                - **Comprehensive & Structured:** Do not give brief, one-sentence answers. Elaborate thoroughly using the context. Use paragraphs, bold text, and bullet points to make information easy to read.
                ###  CRITICAL RULES (STRICT COMPLIANCE):
                1)  GROUNDING (NO HALLUCINATION): Base your answers STRICTLY and EXCLUSIVELY on the "PROVIDED CONTEXT". Do not invent names, rules, majors, or any other facts.
                
                2)  CHRONOLOGICAL AWARENESS (TIME AWARENESS):
                   - The provided context may contain documents, news, or rules from various past years.
                   - Always synthesize information chronologically. Clearly distinguish between "current" (%d) and "historical" or "past" information.
                   - Whenever stating a fact (a person's position, a university rule, an event), explicitly mention the date, year, or academic term associated with it in the context (e.g., "وفقاً لسجلات عام 2023...", "بدءاً من الفصل الدراسي...").
                   - If data conflicts across different years, prioritize the most recent data as the "current" status, but respectfully acknowledge the past records if relevant to the user's query.

                3)  MISSING INFORMATION: If the provided context lacks the specific answer, DO NOT guess. Politely state that the specific information is not available in your current records.
                
                4)  CONTEXTUAL CONTINUITY: Always review the "CONVERSATION HISTORY" to understand pronouns or references to previous topics (e.g., "What about his office?", "How do I register for it?").
                
                5)  LANGUAGE MATCHING: You MUST answer entirely in the EXACT SAME LANGUAGE as the "NEW USER QUESTION". Translate context internally if necessary.
                """, currentYear, currentYear));

        if (history != null && !history.isEmpty()) {
            sb.append("\n--- CONVERSATION HISTORY ---\n");

            int lastIndexToInclude = history.size();
            Message lastMsg = history.get(history.size() - 1);
            if (lastMsg.getContent().trim().equalsIgnoreCase(question.trim())) {
                lastIndexToInclude = history.size() - 1;
            }

            int start = Math.max(0, lastIndexToInclude - 10);
            for (int j = start; j < lastIndexToInclude; j++) {
                Message m = history.get(j);
                String role = (m.getSender() == SenderType.USER) ? "User" : "YU-Bot";
                sb.append(role).append(": ").append(m.getContent()).append("\n");
            }
            sb.append("--- END OF HISTORY ---\n");
        }

        sb.append("\nNEW USER QUESTION: ").append(question).append("\n");

        sb.append("\n--- PROVIDED CONTEXT ---\n");
        if (chunks.isEmpty()) {
            sb.append("No specific context found for this query.\n");
        } else {
            int i = 1;
            for (RetrievedChunk c : chunks) {
                if (c.getHeading() != null && !c.getHeading().isBlank()) {
                    sb.append("[Source ").append(i++).append(" - ").append(c.getHeading()).append("]:\n").append(c.getText()).append("\n\n");
                } else {
                    sb.append("[Source ").append(i++).append("]:\n").append(c.getText()).append("\n\n");
                }
            }
        }
        sb.append("--- END OF CONTEXT ---\n");

        // Final trigger for the LLM response
        sb.append("\nYour Helpful Answer:");

        return sb.toString();
    }
}