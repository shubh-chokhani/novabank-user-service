package com.novabank.userservice.service;

import static com.novabank.userservice.domain.UserStatus.ACTIVE;
import static com.novabank.userservice.domain.UserStatus.PENDING_VERIFICATION;
import static java.util.Objects.isNull;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.novabank.userservice.domain.User;
import com.novabank.userservice.domain.UserStatus;
import com.novabank.userservice.exception.LoginException;
import com.novabank.userservice.repository.UserRepository;
import com.novabank.userservice.security.JwtService;

@Service
public class UserService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public UserService(PasswordEncoder passwordEncoder, UserRepository userRepository, JwtService jwtService) {
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    public void registerUser(String email, String password) {
        String hashedPassword = passwordEncoder.encode(password);
        createUser(email, hashedPassword, PENDING_VERIFICATION);
    }

    public String loginUser(String email, String password) {
        User user = userRepository.findByEmail(email);
        if (isNull(user)) {
            throw new LoginException("User not found", email);
        }
        if (user.getStatus() != ACTIVE) {
            throw new LoginException("User is not active", email);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new LoginException("Wrong password", email);
        }
        String token = jwtService.generateToken(user.getUserId());
        jwtService.storeToken(user.getUserId(), token);
        return token;
    }

    private void createUser(String email, String hashedPassword, UserStatus status) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(hashedPassword);
        user.setStatus(status);
        userRepository.save(user);
    }
}
