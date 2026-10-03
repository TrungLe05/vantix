package com.dev.user_service.repository;

import com.dev.user_service.entities.User;
import com.dev.user_service.enums.OauthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    Optional<User> findByOauthProviderAndOauthIdAndDeletedAtIsNull(OauthProvider provider, String oauthId);

}
