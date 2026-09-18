package com.xupan.server.system.web;

import com.xupan.server.system.service.TestPlayerAdminService;

import java.util.List;

public record TestPlayerPageResponse(List<TestPlayerAdminResponse> items,
                                     int page, int pageSize, long total) {
    static TestPlayerPageResponse from(TestPlayerAdminService.TestPlayerPage page) {
        return new TestPlayerPageResponse(page.items().stream()
                .map(player -> TestPlayerAdminResponse.from(player, List.of())).toList(),
                page.page(), page.pageSize(), page.total());
    }
}
