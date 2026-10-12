package com.example.ezzyhubai.exception;



public class PromptInjectionException
        extends RuntimeException {

    public PromptInjectionException(
            String message) {

        super(message);
    }
}