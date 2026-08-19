package com.jacksonfalgoust.receiptsplitter;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ScaffoldStubsTests {

    @Autowired
    private EntityManager entityManager;

    @Test
    void jpaRecognizesAllFourDomainEntities() {
        Set<String> entityNames = entityManager.getMetamodel().getEntities().stream()
                .map(type -> type.getJavaType().getSimpleName())
                .collect(Collectors.toSet());

        // Update this list when adding a new @Entity class — this assertion is
        // intentionally exact, not a "contains at least" check.
        assertThat(entityNames).containsExactlyInAnyOrder("Bill", "Item", "Participant", "ItemClaim");
    }
}
