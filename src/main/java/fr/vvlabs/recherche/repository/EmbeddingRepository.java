package fr.vvlabs.recherche.repository;

import fr.vvlabs.recherche.model.EmbeddingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmbeddingRepository extends JpaRepository<EmbeddingEntity, Long> {

    Optional<EmbeddingEntity> findByDocumentId(Long documentId);
}
