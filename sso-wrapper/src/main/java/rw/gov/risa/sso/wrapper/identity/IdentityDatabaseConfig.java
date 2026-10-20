package rw.gov.risa.sso.wrapper.identity;

import java.util.Properties;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;

/**
 * The {@code sso_identity} DataSource, Flyway ({@code classpath:db/identity}) and JPA — wired ONLY
 * when IDENTITY_DATABASE_URL is set (the Boot autoconfigs for all three are excluded globally).
 * Unset is a supported state: mirror-dependent endpoints answer 503 and the reconcile runs
 * without a mirror.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(IdentityDatabaseCondition.class)
@EnableJpaRepositories(basePackageClasses = IdentityDatabaseConfig.class,
        entityManagerFactoryRef = "identityEntityManagerFactory",
        transactionManagerRef = "identityTransactionManager")
public class IdentityDatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(IdentityDatabaseConfig.class);

    @Bean(name = "identityDataSource", destroyMethod = "close")
    DataSource identityDataSource(IdentityDatabaseProperties props) {
        IdentityDatabaseProperties.Connection c = props.connection();
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("sso-identity");
        cfg.setJdbcUrl(c.jdbcUrl());
        if (c.username() != null) {
            cfg.setUsername(c.username());
        }
        if (c.password() != null) {
            cfg.setPassword(c.password());
        }
        cfg.setMaximumPoolSize(Math.max(2, props.poolSize()));
        // Lazy: a mirror that is down must not stop the wrapper booting.
        cfg.setInitializationFailTimeout(-1);
        cfg.setConnectionTimeout(5_000);
        log.info("identity mirror: {} (sso_identity, never Keycloak's database)", c);
        return new HikariDataSource(cfg);
    }

    @Bean(name = "identityFlyway")
    Flyway identityFlyway(@Qualifier("identityDataSource") DataSource ds, IdentityDatabaseProperties props) {
        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/identity")
                .table("flyway_identity_history")
                .load();
        if (props.migrate()) {
            try {
                flyway.migrate();
            } catch (RuntimeException e) {
                // Counted, never fatal: the wrapper must still serve logins with the mirror down.
                log.error("identity mirror: Flyway migration failed ({}); mirror features will fail until fixed",
                        e.getMessage());
            }
        }
        return flyway;
    }

    @Bean(name = "identityEntityManagerFactory")
    @DependsOn("identityFlyway")
    LocalContainerEntityManagerFactoryBean identityEntityManagerFactory(
            @Qualifier("identityDataSource") DataSource ds) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(ds);
        emf.setPackagesToScan(IdentityDatabaseConfig.class.getPackageName());
        emf.setPersistenceUnitName("sso-identity");
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties jpa = new Properties();
        jpa.put("hibernate.hbm2ddl.auto", "none");
        jpa.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        // Do not touch the database while the context starts (a down mirror must not block boot).
        jpa.put("hibernate.boot.allow_jdbc_metadata_access", "false");
        jpa.put("hibernate.jdbc.time_zone", "UTC");
        emf.setJpaProperties(jpa);
        return emf;
    }

    @Bean(name = "identityTransactionManager")
    PlatformTransactionManager identityTransactionManager(
            @Qualifier("identityEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean
    IdentityStore identityStore(@Qualifier("identityDataSource") DataSource ds,
                                @Qualifier("identityTransactionManager") PlatformTransactionManager tm,
                                EmployeeRepository employees, EmploymentRepository employments, JobRepository jobs,
                                EmployeeSsoLinkRepository links, SectorRepository sectors,
                                InstitutionRepository institutions) {
        TransactionTemplate tx = new TransactionTemplate(tm);
        TransactionTemplate ro = new TransactionTemplate(tm);
        ro.setReadOnly(true);
        return new IdentityStore(new NamedParameterJdbcTemplate(ds), tx, ro, employees, employments, jobs, links,
                sectors, institutions);
    }
}
