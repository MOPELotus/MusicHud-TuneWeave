package indi.mopelotus.musichud.beans.user;

/** Public local account selector data. Never includes credentials. */
public record SavedAccount(String id, String userId, String nickname, String avatarUrl, boolean selected) {}
