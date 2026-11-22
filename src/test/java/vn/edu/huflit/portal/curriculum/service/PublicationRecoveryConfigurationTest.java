package vn.edu.huflit.portal.curriculum.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import static org.junit.jupiter.api.Assertions.*;

class PublicationRecoveryConfigurationTest {
    @Test void explicitReadOnlyStartupDoesNotCreateRecoveryOrAccessMongo() {
        new ApplicationContextRunner().withUserConfiguration(PublicationRecoveryService.class)
            .withPropertyValues("portal.publication-recovery.enabled=false")
            .run(context->{assertNull(context.getStartupFailure());assertFalse(context.containsBean("publicationRecoveryService"));
                assertTrue(context.getBeansOfType(PublicationRecoveryService.class).isEmpty());
                assertTrue(context.getBeansOfType(MongoTemplate.class).isEmpty());});
    }
}
