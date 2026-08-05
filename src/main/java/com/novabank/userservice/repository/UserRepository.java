package com.novabank.userservice.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.novabank.userservice.domain.User;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    
}
