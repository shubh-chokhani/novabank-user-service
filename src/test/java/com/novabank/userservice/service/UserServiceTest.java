package com.novabank.userservice.service;

import static com.novabank.userservice.domain.UserStatus.ACTIVE;
import static com.novabank.userservice.domain.UserStatus.PENDING_VERIFICATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.novabank.userservice.domain.User;
import com.novabank.userservice.exception.LoginException;
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

        ArgumentCaptor<User> userCaptor =
                ArgumentCaptor.forClass(User.class);

        userService.registerUser(email, password);

        verify(passwordEncoder).encode(password);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();

        assertEquals(email, savedUser.getEmail());
        assertEquals(hashedPassword, savedUser.getPasswordHash());
        assertEquals(PENDING_VERIFICATION, savedUser.getStatus());
    }

    @Test
    void loginUser_shouldThrowException_whenUserDoesNotExist() {
        String email = "test@example.com";
        String password = "Password123";

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.empty());

        LoginException exception = assertThrows(
                LoginException.class,
                () -> userService.loginUser(email, password));

        assertEquals("User not found", exception.getMessage());

        verify(userRepository).findByEmail(email);
        verifyNoInteractions(passwordEncoder, jwtService);
    }

    @Test
    void loginUser_shouldThrowException_whenUserIsNotActive() {
        String email = "test@example.com";
        String password = "Password123";

        User user = mock(User.class);

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.of(user));

        when(user.getStatus())
                .thenReturn(PENDING_VERIFICATION);

        LoginException exception = assertThrows(
                LoginException.class,
                () -> userService.loginUser(email, password));

        assertEquals("User is not active", exception.getMessage());

        verify(userRepository).findByEmail(email);
        verify(user).getStatus();
        verifyNoInteractions(passwordEncoder, jwtService);
    }

    @Test
    void loginUser_shouldThrowException_whenPasswordIsWrong() {
        String email = "test@example.com";
        String password = "WrongPassword";
        String hashedPassword = "hashed-password";

        User user = mock(User.class);

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.of(user));

        when(user.getStatus())
                .thenReturn(ACTIVE);

        when(user.getPasswordHash())
                .thenReturn(hashedPassword);

        when(passwordEncoder.matches(password, hashedPassword))
                .thenReturn(false);

        LoginException exception = assertThrows(
                LoginException.class,
                () -> userService.loginUser(email, password));

        assertEquals("Wrong password", exception.getMessage());

        verify(userRepository).findByEmail(email);
        verify(user).getStatus();
        verify(user).getPasswordHash();
        verify(passwordEncoder).matches(password, hashedPassword);
        verifyNoInteractions(jwtService);
    }

    @Test
    void loginUser_shouldGenerateAndStoreToken_whenCredentialsAreValid() {
        String email = "test@example.com";
        String password = "Password123";
        String hashedPassword = "hashed-password";
        String token = "jwt-token";
        UUID userId = UUID.randomUUID();

        User user = mock(User.class);

        when(userRepository.findByEmail(email))
                .thenReturn(Optional.of(user));

        when(user.getStatus())
                .thenReturn(ACTIVE);

        when(user.getPasswordHash())
                .thenReturn(hashedPassword);

        when(user.getUserId())
                .thenReturn(userId);

        when(passwordEncoder.matches(password, hashedPassword))
                .thenReturn(true);

        when(jwtService.generateToken(userId))
                .thenReturn(token);

        String result = userService.loginUser(email, password);

        assertEquals(token, result);

        verify(userRepository).findByEmail(email);
        verify(user).getStatus();
        verify(user).getPasswordHash();
        verify(user).getUserId();
        verify(passwordEncoder).matches(password, hashedPassword);
        verify(jwtService).generateToken(userId);
        verify(jwtService).storeToken(userId, token);
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