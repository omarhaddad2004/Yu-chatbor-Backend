package jo.edu.yu.yu_chatbot.document;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "chunks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private DocumentEntity document;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(columnDefinition = "text", nullable = false)
    private String text;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(length = 500)
    private String heading;

    @Column
    private Float importance;

    @Column(columnDefinition = "text")
    private String keywords;
}
