package com.meetingintelligence.application.exception;

/** Login recusado (spec 007): credencial inválida, de outro app ou não verificada. Vira 401. */
public class AuthenticationFailedException extends RuntimeException {
    public AuthenticationFailedException(String message) {
        super(message);
    }
}
