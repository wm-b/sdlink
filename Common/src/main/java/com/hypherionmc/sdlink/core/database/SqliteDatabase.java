package com.hypherionmc.sdlink.core.database;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Storage for account links, hidden players, and short-lived verification codes. */
public final class SqliteDatabase {

    public enum LinkStatus { SUCCESS, CODE_NOT_FOUND, ALREADY_VERIFIED }

    private static final long CODE_LIFETIME_SECONDS = Duration.ofHours(1).toSeconds();
    private final File folder;
    private final File databaseFile;
    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();

    public SqliteDatabase(String folderPath) {
        folder = new File(folderPath);
        databaseFile = new File(folder, "sdlink.db");
    }

    public synchronized boolean initialize() {
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("Could not create storage directory: " + folder);
        }

        boolean existingDatabase = databaseFile.exists();
        boolean migrated = false;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver is missing", e);
        }

        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                createSchema(connection);
                if (!migrationComplete(connection)) {
                    File accountsFile = new File(folder, "verifiedaccounts.json");
                    File hiddenFile = new File(folder, "hiddenplayers.json");
                    if (accountsFile.exists()) {
                        importAccounts(connection, accountsFile);
                        if (hiddenFile.exists()) importHiddenPlayers(connection, hiddenFile);
                        migrated = true;
                    } else if (hiddenFile.exists()) {
                        throw new IllegalStateException("Legacy hidden players exist but verifiedaccounts.json is missing");
                    } else if (existingDatabase) {
                        try (Statement statement = connection.createStatement();
                             ResultSet rows = statement.executeQuery("SELECT (SELECT COUNT(*) FROM accounts) + (SELECT COUNT(*) FROM hidden_players)")) {
                            if (rows.next() && rows.getInt(1) != 0)
                                throw new IllegalStateException("Existing SQLite rows have no completed migration");
                        }
                    }
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("INSERT INTO storage_meta(key, value) VALUES('schema_version', '1')");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM pending_verifications WHERE expires_at <= ?")) {
                    statement.setLong(1, Instant.now().getEpochSecond());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize SQLite storage", e);
        }
        return migrated;
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS storage_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, username TEXT, in_game_name TEXT, discord_id TEXT, is_offline INTEGER NOT NULL DEFAULT 0)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS accounts_discord_id ON accounts(discord_id)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS hidden_players (identifier TEXT PRIMARY KEY, display_name TEXT, type TEXT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS pending_verifications (account_uuid TEXT PRIMARY KEY REFERENCES accounts(uuid) ON DELETE CASCADE, code TEXT NOT NULL UNIQUE, expires_at INTEGER NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS pending_expiry ON pending_verifications(expires_at)");
        }
    }

    private boolean migrationComplete(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM storage_meta WHERE key = 'schema_version'");
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) return false;
            if (!"1".equals(result.getString(1))) throw new IllegalStateException("Unsupported storage schema version");
            return true;
        }
    }

    private interface LegacyRow<T> { void accept(T row) throws SQLException; }

    private <T> void importFile(File file, Class<T> type, LegacyRow<T> consumer) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String header;
            do {
                header = reader.readLine();
            } while (header != null && header.isBlank());
            JsonObject metadata = header == null ? null : JsonParser.parseString(header).getAsJsonObject();
            if (metadata == null || !metadata.has("schemaVersion")) {
                throw new IllegalStateException("Invalid legacy storage header in " + file);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                T row = gson.fromJson(line, type);
                if (row == null) throw new IllegalStateException("Null legacy row in " + file);
                consumer.accept(row);
            }
        }
    }

    private void importAccounts(Connection connection, File file) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO accounts(uuid, username, in_game_name, discord_id, is_offline) VALUES(?, ?, ?, ?, ?)")) {
            importFile(file, SDLinkAccount.class, account -> {
                if (account.getUuid() == null || account.getUuid().isBlank()) throw new IllegalStateException("Legacy account has no UUID");
                insert.setString(1, account.getUuid());
                insert.setString(2, account.getUsername());
                insert.setString(3, account.getInGameName());
                insert.setString(4, account.getDiscordID());
                insert.setInt(5, account.isOffline() ? 1 : 0);
                insert.executeUpdate();
            });
        }
        // Legacy verification codes have no creation time, so they cannot be safely migrated.
    }

    private void importHiddenPlayers(Connection connection, File file) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO hidden_players(identifier, display_name, type) VALUES(?, ?, ?)")) {
            importFile(file, HiddenPlayers.class, player -> {
                if (player.getIdentifier() == null || player.getIdentifier().isBlank()) throw new IllegalStateException("Legacy hidden player has no identifier");
                insert.setString(1, player.getIdentifier());
                insert.setString(2, player.getDisplayName());
                insert.setString(3, player.getType());
                insert.executeUpdate();
            });
        }
    }

    private SDLinkAccount accountFrom(ResultSet result) throws SQLException {
        SDLinkAccount account = new SDLinkAccount();
        account.setUuid(result.getString("uuid"));
        account.setUsername(result.getString("username"));
        account.setInGameName(result.getString("in_game_name"));
        account.setDiscordID(result.getString("discord_id"));
        account.setOffline(result.getInt("is_offline") != 0);
        return account;
    }

    public synchronized SDLinkAccount findAccount(String uuid) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM accounts WHERE uuid = ?")) {
            statement.setString(1, uuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? accountFrom(result) : null;
            }
        } catch (SQLException e) { throw new IllegalStateException("Failed to find account", e); }
    }

    public synchronized List<SDLinkAccount> allAccounts() {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM accounts");
             ResultSet result = statement.executeQuery()) {
            List<SDLinkAccount> accounts = new ArrayList<>();
            while (result.next()) accounts.add(accountFrom(result));
            return accounts;
        } catch (SQLException e) { throw new IllegalStateException("Failed to load accounts", e); }
    }

    public synchronized void upsertAccount(SDLinkAccount account) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO accounts(uuid, username, in_game_name, discord_id, is_offline) VALUES(?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET username=excluded.username, in_game_name=excluded.in_game_name, is_offline=excluded.is_offline")) {
            statement.setString(1, account.getUuid());
            statement.setString(2, account.getUsername());
            statement.setString(3, account.getInGameName());
            statement.setString(4, account.getDiscordID());
            statement.setInt(5, account.isOffline() ? 1 : 0);
            statement.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Failed to store account", e); }
    }

    public synchronized void deleteAccount(String uuid) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM accounts WHERE uuid = ?")) {
            statement.setString(1, uuid);
            statement.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Failed to delete account", e); }
    }

    public synchronized String getOrCreateCode(String uuid) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                long now = Instant.now().getEpochSecond();
                try (PreparedStatement cleanup = connection.prepareStatement("DELETE FROM pending_verifications WHERE expires_at <= ?")) {
                    cleanup.setLong(1, now);
                    cleanup.executeUpdate();
                }
                try (PreparedStatement find = connection.prepareStatement("SELECT code FROM pending_verifications WHERE account_uuid = ? AND expires_at > ?")) {
                    find.setString(1, uuid);
                    find.setLong(2, now);
                    try (ResultSet result = find.executeQuery()) {
                        if (result.next()) {
                            String code = result.getString(1);
                            connection.commit();
                            return code;
                        }
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM pending_verifications WHERE account_uuid = ?")) {
                    delete.setString(1, uuid);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO pending_verifications(account_uuid, code, expires_at) VALUES(?, ?, ?)")) {
                    int firstCode = random.nextInt(9000);
                    for (int attempt = 0; attempt < 9000; attempt++) {
                        String code = String.valueOf(1000 + (firstCode + attempt) % 9000);
                        insert.setString(1, uuid);
                        insert.setString(2, code);
                        insert.setLong(3, now + CODE_LIFETIME_SECONDS);
                        if (insert.executeUpdate() == 1) {
                            connection.commit();
                            return code;
                        }
                    }
                }
                throw new IllegalStateException("No verification codes are available");
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) { throw new IllegalStateException("Failed to issue verification code", e); }
    }

    public synchronized SDLinkAccount findAccountByCode(String code) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT a.* FROM accounts a JOIN pending_verifications p ON p.account_uuid = a.uuid WHERE p.code = ? AND p.expires_at > ?")) {
            statement.setString(1, code);
            statement.setLong(2, Instant.now().getEpochSecond());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? accountFrom(result) : null; }
        } catch (SQLException e) { throw new IllegalStateException("Failed to find verification code", e); }
    }

    public synchronized LinkStatus linkAccount(String uuid, String discordId, String code, boolean allowMultiple) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                if (code != null) {
                    try (PreparedStatement check = connection.prepareStatement("SELECT 1 FROM pending_verifications WHERE account_uuid = ? AND code = ? AND expires_at > ?")) {
                        check.setString(1, uuid);
                        check.setString(2, code);
                        check.setLong(3, Instant.now().getEpochSecond());
                        try (ResultSet result = check.executeQuery()) {
                            if (!result.next()) {
                                connection.rollback();
                                return LinkStatus.CODE_NOT_FOUND;
                            }
                        }
                    }
                }
                if (!allowMultiple && code != null) {
                    try (PreparedStatement check = connection.prepareStatement("SELECT 1 FROM accounts WHERE discord_id = ? LIMIT 1")) {
                        check.setString(1, discordId);
                        try (ResultSet result = check.executeQuery()) {
                            if (result.next()) {
                                connection.rollback();
                                return LinkStatus.ALREADY_VERIFIED;
                            }
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement("UPDATE accounts SET discord_id = ? WHERE uuid = ?")) {
                    update.setString(1, discordId);
                    update.setString(2, uuid);
                    if (update.executeUpdate() != 1) throw new IllegalStateException("Account disappeared during verification");
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM pending_verifications WHERE account_uuid = ?")) {
                    delete.setString(1, uuid);
                    delete.executeUpdate();
                }
                connection.commit();
                return LinkStatus.SUCCESS;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) { throw new IllegalStateException("Failed to link account", e); }
    }

    public synchronized void unlinkAccounts(String discordId) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("UPDATE accounts SET discord_id = NULL WHERE discord_id = ?")) {
            statement.setString(1, discordId);
            statement.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Failed to unlink accounts", e); }
    }

    public synchronized HiddenPlayers findHiddenPlayer(String identifier) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM hidden_players WHERE identifier = ?")) {
            statement.setString(1, identifier);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? HiddenPlayers.of(result.getString("identifier"), result.getString("display_name"), result.getString("type")) : null;
            }
        } catch (SQLException e) { throw new IllegalStateException("Failed to find hidden player", e); }
    }

    public synchronized List<HiddenPlayers> allHiddenPlayers() {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM hidden_players");
             ResultSet result = statement.executeQuery()) {
            List<HiddenPlayers> players = new ArrayList<>();
            while (result.next()) players.add(HiddenPlayers.of(result.getString("identifier"), result.getString("display_name"), result.getString("type")));
            return players;
        } catch (SQLException e) { throw new IllegalStateException("Failed to load hidden players", e); }
    }

    public synchronized void upsertHiddenPlayer(HiddenPlayers player) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO hidden_players(identifier, display_name, type) VALUES(?, ?, ?) ON CONFLICT(identifier) DO UPDATE SET display_name=excluded.display_name, type=excluded.type")) {
            statement.setString(1, player.getIdentifier());
            statement.setString(2, player.getDisplayName());
            statement.setString(3, player.getType());
            statement.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Failed to store hidden player", e); }
    }

    public synchronized void deleteHiddenPlayer(String identifier) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM hidden_players WHERE identifier = ?")) {
            statement.setString(1, identifier);
            statement.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Failed to delete hidden player", e); }
    }
}
