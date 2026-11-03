package com.xupan.server.displayobserver;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DisplayObserverBindingRepositoryTest {

    @Test
    void insertSqlHasOnePlaceholderForEveryArgument() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DisplayObserverBindingRepository repository = new DisplayObserverBindingRepository(jdbc);
        var target = new DisplayObserverBindingRepository.TargetSnapshot(
                12, 34, 56, "MACHINE", "machine-user", "机器", "子账号", "BOT001", "展示托");

        repository.insert(target, "AbCdEf0123456789", 78);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), arguments.capture());
        long placeholders = sql.getValue().chars().filter(character -> character == '?').count();
        assertThat(placeholders).isEqualTo(arguments.getValue().length);
        assertThat(arguments.getValue()).hasSize(14);
    }
}
