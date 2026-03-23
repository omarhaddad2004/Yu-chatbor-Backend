package jo.edu.yu.yu_chatbot.chat;

import jo.edu.yu.yu_chatbot.rag.RagResult;
import jo.edu.yu.yu_chatbot.rag.RagService;
import jo.edu.yu.yu_chatbot.security.user.UserEntity;
import jo.edu.yu.yu_chatbot.security.user.UserRepository;
import jo.edu.yu.yu_chatbot.document.ConversationRepository;
import jo.edu.yu.yu_chatbot.document.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux; // ✅ إضافة الإمبورت

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final RagService ragService;

    @Transactional
    public Conversation createConversation(String userEmail) {
        Conversation conversation = new Conversation();
        if (userEmail != null) {
            UserEntity user = userRepository.findByEmail(userEmail)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            conversation.setUser(user);
            conversation.setTitle("New Conversation");
        } else {
            conversation.setTitle("Guest Chat");
        }
        return conversationRepository.save(conversation);
    }

    @Transactional
    public ChatResponse processMessage(UUID conversationId, String userText) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));

        List<Message> history = messageRepository.findByConversation_IdOrderByCreatedAtAsc(conversationId);
        RagResult ragResult = ragService.answerWithHistory(userText, history);

        saveMessage(conversation, SenderType.USER, userText);
        saveMessage(conversation, SenderType.BOT, ragResult.getAnswer());
        updateConversationTitle(conversation, userText);

        return new ChatResponse(ragResult.getAnswer());
    }

    @Transactional
    public Flux<ChatResponse> processMessageStream(UUID conversationId, String userText) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));

        List<Message> history = messageRepository.findByConversation_IdOrderByCreatedAtAsc(conversationId);

        saveMessage(conversation, SenderType.USER, userText);
        updateConversationTitle(conversation, userText);

        StringBuilder fullBotAnswer = new StringBuilder();

        return ragService.streamAnswerWithHistory(userText, history)
                .map(ragResult -> {
                    String textChunk = ragResult.getAnswer() != null ? ragResult.getAnswer() : "";
                    fullBotAnswer.append(textChunk);
                    return new ChatResponse(textChunk);
                })
                .doOnComplete(() -> {
                    saveMessage(conversation, SenderType.BOT, fullBotAnswer.toString());
                });
    }

    public List<Conversation> getUserConversations(String email) {
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return conversationRepository.findByUserIdExplicit(user.getId());
    }

    @Transactional(readOnly = true)
    public List<Message> getChatMessages(UUID conversationId, String userEmail) {
        Conversation conv = conversationRepository.findByIdWithUser(conversationId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (conv.getUser() != null && !conv.getUser().getEmail().equals(userEmail)) {
            throw new RuntimeException("Access Denied: You do not own this chat!");
        }
        return messageRepository.findByConversation_IdOrderByCreatedAtAsc(conversationId);
    }

    @Transactional
    public void deleteConversation(UUID conversationId, String userEmail) {
        Conversation conv = conversationRepository.findByIdWithUser(conversationId)
                .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (conv.getUser() == null || !conv.getUser().getEmail().equals(userEmail)) {
            throw new RuntimeException("Access Denied: You cannot delete this chat!");
        }
        conversationRepository.delete(conv);
    }

    private void saveMessage(Conversation conversation, SenderType sender, String content) {
        Message message = new Message();
        message.setConversation(conversation);
        message.setSender(sender);
        message.setContent(content);
        messageRepository.save(message);
    }

    private void updateConversationTitle(Conversation conversation, String userText) {
        if (conversation.getMessages().size() <= 2) {
            String title = userText.length() > 30 ? userText.substring(0, 30) + "..." : userText;
            conversation.setTitle(title);
        }
        conversationRepository.save(conversation);
    }
}