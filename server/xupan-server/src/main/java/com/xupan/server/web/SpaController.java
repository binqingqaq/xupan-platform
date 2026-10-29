package com.xupan.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping({
            "/login", "/login/",
            "/platform-admin", "/platform-admin/",
            "/platform-admin/sub-accounts", "/platform-admin/sub-accounts/",
            "/platform-admin/machines", "/platform-admin/machines/",
            "/platform-admin/reports", "/platform-admin/reports/",
            "/platform-admin/draw-history", "/platform-admin/draw-history/",
            "/platform-admin/unsettled-orders", "/platform-admin/unsettled-orders/",
            "/platform-admin/order-corrections", "/platform-admin/order-corrections/",
            "/platform-admin/online-players", "/platform-admin/online-players/",
            "/platform-admin/settings", "/platform-admin/settings/",
            "/platform-admin/report-networks", "/platform-admin/report-networks/",
            "/platform-admin/games", "/platform-admin/games/",
            // 中心上游管理（阶段 B）：SPA 路由是逐个白名单，新增页面必须同步登记，
            // 否则直接访问/刷新该路径会落到静态资源解析并返回 500。
            "/platform-admin/center", "/platform-admin/center/",
            "/platform-admin/password", "/platform-admin/password/",
            "/platform-admin/login", "/platform-admin/login/",
            "/forbidden", "/forbidden/",
            "/room", "/room/",
            "/player-login", "/player-login/",
            "/console", "/console/",
            "/hby019-demo", "/hby019-demo/",
            "/hby019-demo/login", "/hby019-demo/login/",
            "/hby019-demo/console", "/hby019-demo/console/",
            "/hby019-demo/showdetails", "/hby019-demo/showdetails/",
            "/hby019-demo/portal", "/hby019-demo/portal/",
            "/hby019-demo/player.html", "/hby019-demo/player.html/",
            "/hby019-demo/player-ui1.html", "/hby019-demo/player-ui1.html/",
            "/console/agents", "/console/agents/",
            "/console/stash", "/console/stash/",
            "/agent", "/agent/",
            "/console/operations", "/console/operations/",
            "/console/users", "/console/users/",
            "/console/players", "/console/players/",
            "/console/robots", "/console/robots/",
            "/admin", "/admin/",
            "/admin/agents", "/admin/agents/",
            "/admin/operations", "/admin/operations/",
            "/admin/users", "/admin/users/",
            "/admin/test-players", "/admin/test-players/",
            "/admin/robots", "/admin/robots/"
    })
    public String forwardToFrontend() {
        return "forward:/index.html";
    }

    @GetMapping("/console/login/{username}")
    public String forwardToMachineLogin() {
        return "forward:/index.html";
    }

    @GetMapping({"/display", "/display/", "/display/mobile", "/display/mobile/"})
    public String forwardToMobileDisplayReplica() {
        return "forward:/display/mobile-replica/index.html";
    }

    @GetMapping({"/33/{linkPath}", "/33/{linkPath}/"})
    public String forwardPlayerLink() {
        return "forward:/index.html";
    }
}
