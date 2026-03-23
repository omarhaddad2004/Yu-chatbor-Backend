package jo.edu.yu.yu_chatbot.document;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    boolean existsBySourceUrl(String sourceUrl);

}
