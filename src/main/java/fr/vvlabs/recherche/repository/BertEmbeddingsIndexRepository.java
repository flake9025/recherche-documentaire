package fr.vvlabs.recherche.repository;

import fr.vvlabs.recherche.model.BertEmbeddingsIndexEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BertEmbeddingsIndexRepository extends JpaRepository<BertEmbeddingsIndexEntity, Long> {

    Optional<BertEmbeddingsIndexEntity> findByIndexName(String indexName);
}
