/*
 * This file is part of sdlink-core, licensed under the MIT License (MIT).
 * Copyright HypherionSA and Contributors
 */
package com.hypherionmc.sdlink.core.managers;

import com.hypherionmc.sdlink.SDLinkConstants;
import com.hypherionmc.sdlink.core.database.HiddenPlayers;
import com.hypherionmc.sdlink.core.database.SDLinkAccount;
import com.hypherionmc.sdlink.core.database.SqliteDatabase;

import java.util.List;

/**
 * @author HypherionSA
 * Compatibility facade for the two persistent collections.
 */
public final class DatabaseManager {

    public static final DatabaseManager INSTANCE = new DatabaseManager();

    private final SqliteDatabase database = new SqliteDatabase("sdlinkstorage");

    private DatabaseManager() {}

    public void initialize() {
        if (database.initialize()) {
            SDLinkConstants.LOGGER.info("Migrated {} accounts and {} hidden players to SQLite. Legacy JSON files were retained as backups.",
                    database.allAccounts().size(), database.allHiddenPlayers().size());
        }
    }

    public void updateEntry(Object entry) {
        if (entry instanceof SDLinkAccount account) database.upsertAccount(account);
        else if (entry instanceof HiddenPlayers player) database.upsertHiddenPlayer(player);
        else throw new IllegalArgumentException("Unknown storage type: " + entry.getClass());
    }

    public void deleteEntry(Object entry) {
        if (entry instanceof SDLinkAccount account) database.deleteAccount(account.getUuid());
        else if (entry instanceof HiddenPlayers player) database.deleteHiddenPlayer(player.getIdentifier());
        else throw new IllegalArgumentException("Unknown storage type: " + entry.getClass());
    }

    public void deleteEntry(Object entry, Class<?> ignored) {
        deleteEntry(entry);
    }

    @SuppressWarnings("unchecked")
    public <T> T findById(Object id, Class<T> entityClass) {
        if (entityClass == SDLinkAccount.class) return entityClass.cast(database.findAccount(String.valueOf(id)));
        if (entityClass == HiddenPlayers.class) return entityClass.cast(database.findHiddenPlayer(String.valueOf(id)));
        throw new IllegalArgumentException("Unknown storage type: " + entityClass);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getCollection(Class<T> entityClass) {
        if (entityClass == SDLinkAccount.class) return (List<T>) database.allAccounts();
        if (entityClass == HiddenPlayers.class) return (List<T>) database.allHiddenPlayers();
        throw new IllegalArgumentException("Unknown storage type: " + entityClass);
    }

    public <T> List<T> findAll(Class<T> tClass) {
        return getCollection(tClass);
    }

    public String getOrCreateVerificationCode(String uuid) {
        return database.getOrCreateCode(uuid);
    }

    public SDLinkAccount findAccountByVerificationCode(String code) {
        return database.findAccountByCode(code);
    }

    public SqliteDatabase.LinkStatus linkAccount(String uuid, String discordId, String code, boolean allowMultiple) {
        return database.linkAccount(uuid, discordId, code, allowMultiple);
    }

    public void unlinkAccounts(String discordId) {
        database.unlinkAccounts(discordId);
    }
}
