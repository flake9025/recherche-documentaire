package fr.vvlabs.recherche.repository;

import fr.vvlabs.recherche.model.LuceneVectorIndexEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LuceneVectorIndexRepository extends JpaRepository<LuceneVectorIndexEntity, Long> {

    Optional<LuceneVectorIndexEntity> findByIndexName(String indexName);
}
