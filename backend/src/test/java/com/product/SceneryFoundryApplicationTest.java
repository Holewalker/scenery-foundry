package com.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SceneryFoundryApplicationTest {

    @Test
    void applicationTypeIsAvailable() {
        assertThat(SceneryFoundryApplication.class).isNotNull();
    }
}
