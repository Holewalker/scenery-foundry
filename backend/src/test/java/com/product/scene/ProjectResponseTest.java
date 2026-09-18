package com.product.scene;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProjectResponseTest {

    @Test
    void fromCarriesIdAndNameButNeverTheOwnerId() {
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();
        var project = new Project(id, owner, "My Scene");

        var response = ProjectResponse.from(project);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.name()).isEqualTo("My Scene");
        assertThat(response.toString()).doesNotContain(owner.toString());
    }

    @Test
    void fromPassesThroughANullNameForLegacyProjects() {
        var project = new Project(UUID.randomUUID(), UUID.randomUUID());

        assertThat(ProjectResponse.from(project).name()).isNull();
    }
}
