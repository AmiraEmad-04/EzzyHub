package com.ezzyhub.gateway.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the in-memory sliding-window {@link RateLimiter}.
 */
class RateLimiterTest {

    @Test
    void allowsUpToLimit_thenBlocks() {
        RateLimiter limiter = new RateLimiter();
        // window large enough that time does not advance out of it during the test
        assertThat(limiter.tryAcquire("ip1", 3, 10_000)).isTrue();
        assertThat(limiter.tryAcquire("ip1", 3, 10_000)).isTrue();
        assertThat(limiter.tryAcquire("ip1", 3, 10_000)).isTrue();
        // 4th within the window is rejected
        assertThat(limiter.tryAcquire("ip1", 3, 10_000)).isFalse();
    }

    @Test
    void differentKeysAreIndependent() {
        RateLimiter limiter = new RateLimiter();
        assertThat(limiter.tryAcquire("ipA", 1, 10_000)).isTrue();
        assertThat(limiter.tryAcquire("ipA", 1, 10_000)).isFalse();   // A exhausted
        assertThat(limiter.tryAcquire("ipB", 1, 10_000)).isTrue();    // B unaffected
    }

    @Test
    void windowSlides_allowsAgainAfterWindowExpires() throws InterruptedException {
        RateLimiter limiter = new RateLimiter();
        long window = 80;
        assertThat(limiter.tryAcquire("ip2", 1, window)).isTrue();
        assertThat(limiter.tryAcquire("ip2", 1, window)).isFalse();   // still inside window
        Thread.sleep(window + 40);                                    // let the window pass
        assertThat(limiter.tryAcquire("ip2", 1, window)).isTrue();    // old hit expired
    }

    @Test
    void limitOfZero_blocksEverything() {
        RateLimiter limiter = new RateLimiter();
        assertThat(limiter.tryAcquire("ip3", 0, 10_000)).isFalse();
    }
}
