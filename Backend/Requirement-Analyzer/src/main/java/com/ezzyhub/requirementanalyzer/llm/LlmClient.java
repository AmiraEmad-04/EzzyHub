package com.ezzyhub.requirementanalyzer.llm;

/**
 * A provider-agnostic seam for "send a prompt, get text back". Callers
 * (e.g. AiSemanticValidator) depend only on this interface, never on a
 * specific vendor's HTTP contract — swapping providers is a config change
 * (ai.provider=groq|openai), not a code change.
 */
public interface LlmClient {

    /** Human-readable provider name, used only for logging (e.g. "Groq", "OpenAI"). */
    String providerName();

    /** True iff this provider's API key is configured and it can be called. */
    boolean isConfigured();

    /**
     * Sends the prompt to the LLM and returns the raw assistant text
     * (the vendor's response envelope has already been unwrapped).
     * Throws LlmClientException for any transport/HTTP failure.
     */
    String complete(String prompt) throws LlmClientException;
}
