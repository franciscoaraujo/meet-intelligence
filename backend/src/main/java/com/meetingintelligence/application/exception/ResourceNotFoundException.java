package com.meetingintelligence.application.exception;

/** Recurso inexistente ou não pertencente à organização do usuário (mapeado para HTTP 404). */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
