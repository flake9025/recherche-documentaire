package fr.vvlabs.recherche.repository;

import fr.vvlabs.recherche.model.IndexEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository pour l'entitÃ© Index
 */
@Repository
public interface IndexRepository extends JpaRepository<IndexEntity, Long> {

    Optional<IndexEntity> findByIndexName(String indexName);
}
