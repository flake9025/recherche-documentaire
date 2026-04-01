package fr.vvlabs.recherche.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * Transporte les metadonnees documentaires exposees par l'application.
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
public class DocumentDTO {

    private Long id;

    @NotBlank(message = "Le titre du document est obligatoire")
    private String titre;

    @NotBlank(message = "L'auteur du depot est obligatoire")
    private String auteur;

    @NotBlank(message = "La categorie est obligatoire")
    @Pattern(regexp = "rapport|facture|note", message = "Categorie invalide")
    private String categorie;

    @NotBlank(message = "Le nom du fichier est obligatoire")
    private String nomFichier;

    private Long tailleFichier;

    private LocalDateTime depotDateTime;

    private boolean ocrIndexDone;
}
