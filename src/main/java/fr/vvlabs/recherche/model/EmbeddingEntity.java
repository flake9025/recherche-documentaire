package fr.vvlabs.recherche.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_embedding")
@Getter
@Setter
@Accessors(chain = true)
public class EmbeddingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, unique = true)
    private Long documentId;

    @Column(name = "title", nullable = false, length = 1000)
    private String title;

    @Column(name = "author", nullable = false, length = 255)
    private String author;

    @Column(name = "category", nullable = false, length = 255)
    private String category;

    @Column(name = "filename", nullable = false, length = 1000)
    private String filename;

    @Column(name = "depot_datetime")
    private LocalDateTime depotDateTime;

    @Lob
    @Column(name = "content_text")
    private String contentText;

    @Lob
    @Column(name = "embedding_data", nullable = false)
    private byte[] embeddingData;
}
