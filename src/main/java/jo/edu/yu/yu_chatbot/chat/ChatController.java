package jo.edu.yu.yu_chatbot.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatService chatService;


    @PostMapping("/new")
    public ResponseEntity<String> startNewConversation(Authentication authentication) {
        String userEmail = (authentication != null && authentication.isAuthenticated())
                ? authentication.getName()
                : null;

        Conversation conversation = chatService.createConversation(userEmail);
        return ResponseEntity.ok(conversation.getId().toString());
    }

    /**
     * 2. إرسال رسالة داخل محادثة موجودة
     */
    @PostMapping(value = "/{conversationId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatResponse> streamMessage(@PathVariable UUID conversationId, @RequestBody ChatRequest request) {
        return chatService.processMessageStream(conversationId, request.getQuestion()); // تأكد من اسم الحقل getQuestion() أو getMessage()
    }
    @PostMapping("/{conversationId}/send")
    public ResponseEntity<ChatResponse> sendMessage(
            @PathVariable UUID conversationId,
            @RequestBody ChatRequest request
    ) {
        ChatResponse response = chatService.processMessage(conversationId, request.getQuestion());
        return ResponseEntity.ok(response);
    }

    // ==========================================
    // 👇👇👇 الإضافات الجديدة (الهامة جداً) 👇👇👇
    // ==========================================

    /**
     * 3. جلب قائمة المحادثات (History)
     * هذا الرابط هو اللي بيعبي السايد بار في الفرونت إند
     */
    @GetMapping("/history")
    public ResponseEntity<List<Conversation>> getMyChatHistory(Authentication authentication) {
        // الجيست ما إله هيستوري محفوظ، فبنرجع خطأ 401 او قائمة فاضية
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        String email = authentication.getName();
        // بننادي الدالة اللي ضفناها في السيرفس
        return ResponseEntity.ok(chatService.getUserConversations(email));
    }

    /**
     * 4. جلب رسائل محادثة معينة (Load Chat)
     * هذا الرابط بشتغل لما تكبس على شات قديم في السايد بار
     */
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<Message>> getChatMessages(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        String email = authentication.getName();
        // بننادي دالة السيرفس اللي فيها Security Check
        return ResponseEntity.ok(chatService.getChatMessages(id, email));
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteConversation(@PathVariable UUID id, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).build();
        }

        String email = authentication.getName();
        chatService.deleteConversation(id, email);
        return ResponseEntity.noContent().build();
    }

}