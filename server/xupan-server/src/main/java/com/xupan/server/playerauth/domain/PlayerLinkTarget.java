package com.xupan.server.playerauth.domain;

public record PlayerLinkTarget(long userId, String userStatus, String accountStatus,
                               String playerKind, String authMode) {
}
