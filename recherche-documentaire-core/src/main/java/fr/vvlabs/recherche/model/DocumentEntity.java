package fr.vvlabs.recherche.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * EntitÃ© reprÃ©sentant les mÃ©tadonnÃ©es d'un document indexÃ©
 */
@Entity
@Table(name = "document_metadata")
@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
@ToString(onlyExplicitlyIncluded = true)
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "titre_document", nullable = false, length = 500)
    private String titreDocument;

    @Column(name = "auteur_depot", nullable = false, length = 255)
    private String auteurDepot;

    @Column(name = "categories_ens", nullable = false, length = 100)
    private String categoriesEns;

    @Column(name = "depot_date_time", nullable = false)
    private LocalDateTime depotDateTime;

    @Column(name = "nom_fichier", length = 500)
    private String nomFichier;

    @Column(name = "taille_fichier")
    private Long tailleFichier;

    @Column(name = "ocr_index_done", nullable = false)
    private boolean ocrIndexDone = false;

    /**
     * Initialise automatiquement la date de dÃ©pÃ´t avant la persistance
     */
    @PrePersist
    protected void onCreate() {
        if (depotDateTime == null) {
            depotDateTime = LocalDateTime.now();
        }
    }
}

