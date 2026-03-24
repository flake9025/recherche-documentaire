package fr.vvlabs.recherche.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * DTO pour la rÃ©ception des mÃ©tadonnÃ©es lors de l'upload d'un document
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
public class DocumentDTO {

    private Long id;

    @NotBlank(message = "Le titre du document est obligatoire")
    private String titre;

    @NotBlank(message = "L'auteur du dÃ©pÃ´t est obligatoire")
    private String auteur;

    @NotBlank(message = "La catÃ©gorie est obligatoire")
    @Pattern(regexp = "rapport|facture|note", message = "CatÃ©gorie invalide")
    private String categorie;

    @NotBlank(message = "Le nom du fichier est obligatoire")
    private String nomFichier;

    private Long tailleFichier;

    private LocalDateTime depotDateTime;

    private boolean ocrIndexDone;
}

