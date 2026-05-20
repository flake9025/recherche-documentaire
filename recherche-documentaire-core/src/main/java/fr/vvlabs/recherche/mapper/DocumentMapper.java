package fr.vvlabs.recherche.mapper;

import fr.vvlabs.recherche.dto.DocumentDTO;
import fr.vvlabs.recherche.model.DocumentEntity;
import fr.vvlabs.recherche.service.cipher.CipherService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Mappe les documents entre la couche web et la couche de persistance.
 */
@Mapper(componentModel = "spring")
public abstract class DocumentMapper {

    @Autowired
    protected CipherService cipherService;

    /**
     * Convertit un DTO applicatif vers l'entite persistable.
     *
     * @param documentDTO document source
     * @return entite a persister
     * @throws Exception si le chiffrement echoue
     */
    @Mapping(target = "titreDocument", expression = "java(encrypt(documentDTO.getTitre()))")
    @Mapping(target = "auteurDepot", expression = "java(encrypt(documentDTO.getAuteur()))")
    @Mapping(target = "categoriesEns", expression = "java(encrypt(documentDTO.getCategorie()))")
    @Mapping(target = "nomFichier", expression = "java(encrypt(documentDTO.getNomFichier()))")
    public abstract DocumentEntity toEntity(DocumentDTO documentDTO) throws Exception;

    /**
     * Convertit une entite persistante vers le DTO expose par l'application.
     *
     * @param documentEntity entite source
     * @return DTO dechiffre
     * @throws Exception si le dechiffrement echoue
     */
    @Mapping(target = "titre", expression = "java(decrypt(documentEntity.getTitreDocument()))")
    @Mapping(target = "auteur", expression = "java(decrypt(documentEntity.getAuteurDepot()))")
    @Mapping(target = "categorie", expression = "java(decrypt(documentEntity.getCategoriesEns()))")
    @Mapping(target = "nomFichier", expression = "java(decrypt(documentEntity.getNomFichier()))")
    public abstract DocumentDTO toDto(DocumentEntity documentEntity) throws Exception;

    protected String encrypt(String value) throws Exception {
        return cipherService.encrypt(value);
    }

    protected String decrypt(String value) throws Exception {
        return cipherService.decrypt(value);
    }
}
