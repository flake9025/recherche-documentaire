package fr.vvlabs.recherche.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Entity
@Table(name = "lucene_index")
@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
@ToString(onlyExplicitlyIncluded = true)
public class IndexEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @Column(nullable = false, unique = true)
    @ToString.Include
    private String indexName; // e.g., "main_index"

    @Lob
    @Column(columnDefinition = "LONGBLOB")
    private byte[] indexData; // Serialized Lucene index

    @Column
    private Long documentCount = 0L;

    @Column
    private LocalDateTime lastUpdated;
}

