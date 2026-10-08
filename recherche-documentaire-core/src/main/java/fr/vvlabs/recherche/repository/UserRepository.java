package fr.vvlabs.recherche.repository;

import fr.vvlabs.recherche.model.UserEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);
    List<UserEntity> findByManagerId(Long managerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<UserEntity> findAllByOrderByIdAsc();
}
