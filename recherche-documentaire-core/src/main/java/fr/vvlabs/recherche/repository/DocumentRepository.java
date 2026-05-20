package fr.vvlabs.recherche.repository;

import fr.vvlabs.recherche.model.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository pour l'entitÃ© Document
 */
@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    Optional<DocumentEntity> findByTitreDocument(String documentTitle);

    List<DocumentEntity> findByOcrIndexDoneFalse();
}

