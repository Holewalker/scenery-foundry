package com.product.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProjectTest {

    @Test
    void carriesTheGivenNameWhenConstructedWithThreeArguments() {
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();

        var project = new Project(id, owner, "My Scene");

        assertThat(project.name()).isEqualTo("My Scene");
    }

    @Test
    void defaultsNameToNullWhenConstructedWithTheLegacyTwoArgumentForm() {
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();

        var project = new Project(id, owner);

        assertThat(project.name()).isNull();
    }

    @Test
    void stillRejectsMissingIdentityWithTheThreeArgumentForm() {
        var owner = UUID.randomUUID();
        assertThatThrownBy(() -> new Project(null, owner, "name")).isInstanceOf(InvalidSceneException.class);
    }
}
