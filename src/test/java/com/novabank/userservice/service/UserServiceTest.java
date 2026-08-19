package com.novabank.userservice.service;

import static com.novabank.userservice.domain.UserStatus.PENDING_VERIFICATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.novabank.userservice.domain.User;
import com.novabank.userservice.repository.UserRepository;
import com.novabank.userservice.security.JwtService;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                passwordEncoder,
                userRepository,
                jwtService);
    }

    @Test
    void registerUser_shouldCreatePendingVerificationUser() {
        String email = "test@example.com";
        String password = "Password123";
        String hashedPassword = "hashed-password";

        when(passwordEncoder.encode(password))
                .thenReturn(hashedPassword);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        userService.registerUser(email, password);

        verify(passwordEncoder).encode(password);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();

        assertEquals(savedUser.getEmail(), email);
        assertEquals(savedUser.getPasswordHash(), hashedPassword);
        assertEquals(savedUser.getStatus(), PENDING_VERIFICATION);
    }

    @Test
    void passwordEncoder_shouldProduceDifferentHashesForSamePassword() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();

        String password = "Password123";

        String hash1 = encoder.encode(password);
        String hash2 = encoder.encode(password);

        assertNotEquals(hash1, hash2);

        assertTrue(encoder.matches(password, hash1));
        assertTrue(encoder.matches(password, hash2));
    }
}