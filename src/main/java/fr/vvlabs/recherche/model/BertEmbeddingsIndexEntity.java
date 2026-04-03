package fr.vvlabs.recherche.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Entity
@Table(name = "bert_embeddings_index")
@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
@ToString(onlyExplicitlyIncluded = true)
public class BertEmbeddingsIndexEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @Column(nullable = false, unique = true)
    @ToString.Include
    private String indexName;

    @Lob
    @Column(columnDefinition = "LONGBLOB")
    private byte[] indexData;

    @Column
    private Long documentCount = 0L;

    @Column
    private LocalDateTime lastUpdated;
}
