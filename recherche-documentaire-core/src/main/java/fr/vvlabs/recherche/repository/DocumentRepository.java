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

    List<DocumentEntity> findByOwnerIdIn(java.util.Collection<Long> ownerIds);

    boolean existsByOwnerId(Long ownerId);

    @org.springframework.data.jpa.repository.Query("select d.id from DocumentEntity d where d.ownerId in :ownerIds")
    List<Long> findIdsByOwnerIdIn(java.util.Collection<Long> ownerIds);

    @org.springframework.data.jpa.repository.Query("select d.id from DocumentEntity d")
    List<Long> findAllIds();
}
