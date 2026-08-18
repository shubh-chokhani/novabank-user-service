package com.novabank.userservice.exception;

public class LoginException extends RuntimeException {
    
    private final String email;

    public LoginException(String message, String email) {
        super(message);
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
