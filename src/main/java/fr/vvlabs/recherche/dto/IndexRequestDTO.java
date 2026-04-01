package fr.vvlabs.recherche.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * Transporte la requete d'indexation unitaire envoyee en multipart.
 */
@Data
public class IndexRequestDTO {

    private MultipartFile file;
    private String titre;
    private String auteur;
    private String categorie;
    private String ocrType;
    private String dateDepot;
}
