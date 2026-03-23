package jo.edu.yu.yu_chatbot.document;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "documents")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 1000)
    private String sourceUrl;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String rawContent;

    @Column(columnDefinition = "text")
    private String cleanedContent;

    @Column(nullable = false, length = 50)
    private String contentType;

    @Column(nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    // One document has many chunks.
    private List<ChunkEntity> chunks;
}
