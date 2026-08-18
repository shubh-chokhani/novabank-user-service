package com.novabank.userservice.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.novabank.userservice.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    User findByEmail(String email);
}
