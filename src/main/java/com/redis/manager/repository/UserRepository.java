package com.redis.manager.repository;

import com.redis.manager.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    List<User> findAllByOrderByCreateTimeDesc();

    List<User> findByStatusOrderByCreateTimeDesc(Integer status);
}
