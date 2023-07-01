package org.apereo.cas.audit;

import org.apereo.cas.audit.spi.BaseAuditConfigurationTests;
import org.apereo.cas.config.CasCoreAuditConfiguration;
import org.apereo.cas.config.CasCoreUtilConfiguration;
import org.apereo.cas.config.CasHibernateJpaConfiguration;
import org.apereo.cas.config.CasSupportJdbcAuditConfiguration;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.util.RandomUtils;

import lombok.Getter;
import lombok.val;
import org.apereo.inspektr.audit.AuditActionContext;
import org.apereo.inspektr.audit.AuditTrailManager;
import org.apereo.inspektr.common.Cleanable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * This is {@link CasSupportJdbcAuditConfigurationTests}.
 *
 * @author Misagh Moayyed
 * @since 5.3.0
 */
@SpringBootTest(classes = {
    CasCoreAuditConfiguration.class,
    CasSupportJdbcAuditConfiguration.class,
    CasHibernateJpaConfiguration.class,
    CasCoreUtilConfiguration.class,
    AopAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
    RefreshAutoConfiguration.class
}, properties = {
    "cas.jdbc.show-sql=false",
    "cas.audit.jdbc.column-length=-1",
    "cas.audit.jdbc.schedule.enabled=true",
    "cas.audit.jdbc.asynchronous=false"
})
@EnableConfigurationProperties(CasConfigurationProperties.class)
@Getter
@Tag("JDBC")
@SuppressWarnings("JavaUtilDate")
class CasSupportJdbcAuditConfigurationTests extends BaseAuditConfigurationTests {

    @Autowired
    @Qualifier("inspektrAuditTrailCleaner")
    private Cleanable inspektrAuditTrailCleaner;

    @Autowired
    @Qualifier("jdbcAuditTrailManager")
    private AuditTrailManager auditTrailManager;

    @Test
    void verifyCleaner() {
        inspektrAuditTrailCleaner.clean();
    }

    @Test
    void verifyLargeResource() {
        val context = new AuditActionContext(
            UUID.randomUUID().toString(),
            RandomUtils.randomAlphabetic(10_000),
            "TEST",
            "CAS", new Date(),
            "1.2.3.4",
            "1.2.3.4",
            "GoogleChrome",
            Map.of());
        getAuditTrailManager().record(context);
    }
}
