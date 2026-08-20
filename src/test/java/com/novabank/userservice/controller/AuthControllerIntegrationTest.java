package com.novabank.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.novabank.userservice.AbstractIntegrationTest;
import com.novabank.userservice.domain.UserStatus;
import com.novabank.userservice.security.JwtService;

import io.jsonwebtoken.Jwts;

@SpringBootTest(properties = {
                "spring.data.redis.timeout=2s"
})
public class AuthControllerIntegrationTest extends AbstractIntegrationTest {

        @Autowired
        private JwtService jwtService;

        @Autowired
        private SecretKey secretKey;

        @Autowired
        private WebApplicationContext context;

        private MockMvc mockMvc;

        @BeforeEach
        void setUpMockMvc() {
                mockMvc = MockMvcBuilders
                                .webAppContextSetup(context)
                                .apply(SecurityMockMvcConfigurers.springSecurity())
                                .build();
        }

        @Test
        void register_withNewEmail_persistsUserAndReturnsGenericResponse() throws Exception {
                String email = "newuser@example.com";
                String password = "Password123!";

                String requestBody = """
                                {
                                    "email": "%s",
                                    "password": "%s"
                                }
                                """.formatted(email, password);

                mockMvc.perform(post("/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isOk())
                                .andExpect(content().string("Check your mailbox"));

                var user = userRepository.findByEmail(email);

                assertThat(user).isPresent();
                assertThat(user.get().getEmail()).isEqualTo(email);
                assertThat(user.get().getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
                assertThat(user.get().getPasswordHash()).isNotEqualTo(password);
        }

        @Test
        void register_sameEmailTwice_returnsIdenticalResponse() throws Exception {
                String email = "duplicate@example.com";
                String password = "Password123!";

                String requestBody = """
                                {
                                    "email": "%s",
                                    "password": "%s"
                                }
                                """.formatted(email, password);

                MvcResult firstResponse = mockMvc.perform(post("/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andReturn();

                MvcResult secondResponse = mockMvc.perform(post("/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andReturn();

                assertThat(secondResponse.getResponse().getStatus())
                                .isEqualTo(firstResponse.getResponse().getStatus());

                assertThat(secondResponse.getResponse().getContentAsString())
                                .isEqualTo(firstResponse.getResponse().getContentAsString());
        }

        @Test
        void register_concurrentlyWithSameEmail_createsExactlyOneUser() throws Exception {
                String email = "duplicate@example.com";
                String password = "Password123!";

                CyclicBarrier barrier = new CyclicBarrier(2);
                ExecutorService executor = Executors.newFixedThreadPool(2);

                Callable<MvcResult> register = () -> {
                        barrier.await();

                        String requestBody = """
                                        {
                                            "email": "%s",
                                            "password": "%s"
                                        }
                                        """.formatted(email, password);

                        return mockMvc.perform(post("/users")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(requestBody))
                                        .andReturn();
                };

                try {
                        List<Future<MvcResult>> results = executor.invokeAll(List.of(register, register));

                        MvcResult result1 = results.get(0).get();
                        MvcResult result2 = results.get(1).get();

                        assertThat(result1.getResponse().getStatus())
                                        .isEqualTo(HttpStatus.OK.value());

                        assertThat(result2.getResponse().getStatus())
                                        .isEqualTo(HttpStatus.OK.value());

                        assertThat(result1.getResponse().getContentAsString())
                                        .isEqualTo(result2.getResponse().getContentAsString());

                        var users = userRepository.findAll();

                        assertThat(users).hasSize(1);

                        var user = users.get(0);

                        assertThat(user.getEmail()).isEqualTo(email);
                        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);

                } finally {
                        executor.shutdownNow();
                }
        }

        @Test
        void login_enumerationFailures_returnIdenticalResponse() throws Exception {
                String password = "Password123!";
                String unverifiedEmail = "unverified@example.com";
                String nonexistentEmail = "nonexistent@example.com";

                String registerRequest = """
                                {
                                    "email": "%s",
                                    "password": "%s"
                                }
                                """.formatted(unverifiedEmail, password);

                mockMvc.perform(post("/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(registerRequest))
                                .andExpect(status().isOk());

                String wrongPasswordRequest = """
                                {
                                    "email": "%s",
                                    "password": "WrongPassword123!"
                                }
                                """.formatted(unverifiedEmail);

                String nonexistentEmailRequest = """
                                {
                                    "email": "%s",
                                    "password": "%s"
                                }
                                """.formatted(nonexistentEmail, password);

                String unverifiedAccountRequest = """
                                {
                                    "email": "%s",
                                    "password": "%s"
                                }
                                """.formatted(unverifiedEmail, password);

                MvcResult wrongPasswordResponse = mockMvc.perform(post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(wrongPasswordRequest))
                                .andReturn();

                MvcResult nonexistentEmailResponse = mockMvc.perform(post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(nonexistentEmailRequest))
                                .andReturn();

                MvcResult unverifiedAccountResponse = mockMvc.perform(post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(unverifiedAccountRequest))
                                .andReturn();

                assertThat(wrongPasswordResponse.getResponse().getStatus())
                                .isEqualTo(HttpStatus.UNAUTHORIZED.value());

                assertThat(nonexistentEmailResponse.getResponse().getStatus())
                                .isEqualTo(HttpStatus.UNAUTHORIZED.value());

                assertThat(unverifiedAccountResponse.getResponse().getStatus())
                                .isEqualTo(HttpStatus.UNAUTHORIZED.value());

                String expectedBody = "Invalid email or password";

                assertThat(wrongPasswordResponse.getResponse().getContentAsString())
                                .isEqualTo(expectedBody);

                assertThat(nonexistentEmailResponse.getResponse().getContentAsString())
                                .isEqualTo(expectedBody);

                assertThat(unverifiedAccountResponse.getResponse().getContentAsString())
                                .isEqualTo(expectedBody);
        }

        @Test
        void requestWithoutAuthorizationHeader_returnsUnauthorized() throws Exception {
                mockMvc.perform(get("/protected"))
                                .andExpect(status().isUnauthorized())
                                .andExpect(content().string("Unauthorized"));
        }

        @Test
        void requestWithMalformedJwt_returnsUnauthorized() throws Exception {
                mockMvc.perform(get("/protected")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                                .andExpect(status().isUnauthorized())
                                .andExpect(content().string("Invalid JWT token"));
        }

        @Test
        void requestWithExpiredJwt_returnsUnauthorized() throws Exception {
                String token = Jwts.builder()
                                .subject(UUID.randomUUID().toString())
                                .issuedAt(Date.from(Instant.now().minusSeconds(120)))
                                .expiration(Date.from(Instant.now().minusSeconds(60)))
                                .signWith(secretKey)
                                .compact();

                mockMvc.perform(get("/protected")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                                .andExpect(status().isUnauthorized())
                                .andExpect(content().string("Token has expired"));
        }

        @Test
        void requestWithRevokedJwt_returnsUnauthorized() throws Exception {
                UUID userId = UUID.randomUUID();

                String token = jwtService.generateToken(userId);

                redisTemplate.opsForValue().set(
                                userId.toString(),
                                "different-token");

                mockMvc.perform(get("/protected")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                                .andExpect(status().isUnauthorized())
                                .andExpect(content().string("Session revoked"));
        }

        @Test
        void requestWhenRedisUnavailable_returnsServiceUnavailable() throws Exception {
                UUID userId = UUID.randomUUID();
                String token = jwtService.generateToken(userId);

                redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
                try {
                        mockMvc.perform(get("/protected")
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                                        .andExpect(status().isServiceUnavailable());

                } finally {
                        redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
                }
        }
}
