// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.pqs;

import com.digitalasset.quickstart.config.PostgresConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Configuration class that sets up a DataSource and JdbcTemplate for interacting with a Postgres database.
 * Only activated when pqs.enabled=true (default: false for Canton 3.4.7 Ledger API-only mode)
 */
@Configuration
@ConditionalOnProperty(name = "pqs.enabled", havingValue = "true", matchIfMissing = false)
public class JdbcDataSource {

    private Logger logger = LoggerFactory.getLogger(JdbcDataSource.class);

    @Autowired
    private PostgresConfig postgresConfig;

    /**
     * Creates a DataSource that connects to a PostgreSQL database using the configuration provided by PostgresConfig.
     *
     * @return A DataSource connected to the PostgreSQL database.
     */
    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        String url = String.format("jdbc:postgresql://%s:%d/%s", postgresConfig.getHost(), postgresConfig.getPort(), postgresConfig.getDatabase());
        logger.info("Connecting to {} as {}", url, postgresConfig.getUsername());
        dataSource.setUrl(url);
        dataSource.setUsername(postgresConfig.getUsername());
        dataSource.setPassword(postgresConfig.getPassword()); // TODO: Make password optional
        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}