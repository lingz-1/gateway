package com.lingshu.core.cache;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PgVectorSemanticCacheStoreTest {

    @Test
    void startupFailsOpenWhenDatabaseIsUnavailable() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("unavailable"));

        assertDoesNotThrow(() -> new PgVectorSemanticCacheStore(
                dataSource,
                JsonMapper.builder().build()
        ));
    }
}
