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
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver is missing", e);
        }

        return transaction("initialize SQLite storage", connection -> {
            createSchema(connection);
            boolean migrated = migrateIfNeeded(connection, existingDatabase);
            deleteExpiredCodes(connection, Instant.now().getEpochSecond());
            return migrated;
        });
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
            return connection;
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
    }

    private interface Transaction<T> { T run(Connection connection) throws Exception; }

    private <T> T transaction(String action, Transaction<T> work) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) { e.addSuppressed(rollbackFailure); }
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to " + action, e);
        }
    }

    private boolean migrateIfNeeded(Connection connection, boolean existingDatabase) throws Exception {
        if (migrationComplete(connection)) return false;

        File accountsFile = new File(folder, "verifiedaccounts.json");
        File hiddenFile = new File(folder, "hiddenplayers.json");
        if (accountsFile.exists()) {
            importAccounts(connection, accountsFile);
            if (hiddenFile.exists()) importHiddenPlayers(connection, hiddenFile);
            markMigrationComplete(connection);
            return true;
        }
        if (hiddenFile.exists())
            throw new IllegalStateException("Legacy hidden players exist but verifiedaccounts.json is missing");
        if (existingDatabase && storedRowCount(connection) != 0)
            throw new IllegalStateException("Existing SQLite rows have no completed migration");

        markMigrationComplete(connection);
        return false;
    }

    private int storedRowCount(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT (SELECT COUNT(*) FROM accounts) + (SELECT COUNT(*) FROM hidden_players)")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private void markMigrationComplete(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO storage_meta(key, value) VALUES('schema_version', '1')");
        }
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
        account.setVerifyCode(result.getString("verify_code"));
        account.setOffline(result.getInt("is_offline") != 0);
        account.markPersisted();
        return account;
    }

    public synchronized SDLinkAccount findAccount(String uuid) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT a.*, p.code AS verify_code FROM accounts a LEFT JOIN pending_verifications p ON p.account_uuid = a.uuid AND p.expires_at > ? WHERE a.uuid = ?")) {
            statement.setLong(1, Instant.now().getEpochSecond());
            statement.setString(2, uuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? accountFrom(result) : null;
            }
        } catch (SQLException e) { throw new IllegalStateException("Failed to find account", e); }
    }

    public synchronized List<SDLinkAccount> allAccounts() {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT a.*, p.code AS verify_code FROM accounts a LEFT JOIN pending_verifications p ON p.account_uuid = a.uuid AND p.expires_at > ?")) {
            statement.setLong(1, Instant.now().getEpochSecond());
            List<SDLinkAccount> accounts = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) accounts.add(accountFrom(result));
            }
            return accounts;
        } catch (SQLException e) { throw new IllegalStateException("Failed to load accounts", e); }
    }

    public synchronized void upsertAccount(SDLinkAccount account) {
        if (account.getUuid() == null || account.getUuid().isBlank())
            throw new IllegalArgumentException("Account UUID is required");
        transaction("store account", connection -> {
            boolean inserted = insertAccountIfMissing(connection, account);
            if (!inserted) updateAccountFields(connection, account);
            if (!inserted && account.isDiscordIdChanged()) updateDiscordLink(connection, account);
            if (account.isVerifyCodeChanged() || (inserted && account.getVerifyCode() != null))
                replaceCode(connection, account);
            return null;
        });
        account.markPersisted();
    }

    private boolean insertAccountIfMissing(Connection connection, SDLinkAccount account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO accounts(uuid, username, in_game_name, discord_id, is_offline) VALUES(?, ?, ?, ?, ?)")) {
            statement.setString(1, account.getUuid());
            statement.setString(2, account.getUsername());
            statement.setString(3, account.getInGameName());
            statement.setString(4, account.getDiscordID());
            statement.setInt(5, account.isOffline() ? 1 : 0);
            return statement.executeUpdate() == 1;
        }
    }

    private void updateAccountFields(Connection connection, SDLinkAccount account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE accounts SET username = ?, in_game_name = ?, is_offline = ? WHERE uuid = ?")) {
            statement.setString(1, account.getUsername());
            statement.setString(2, account.getInGameName());
            statement.setInt(3, account.isOffline() ? 1 : 0);
            statement.setString(4, account.getUuid());
            statement.executeUpdate();
        }
    }

    private void updateDiscordLink(Connection connection, SDLinkAccount account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE accounts SET discord_id = ? WHERE uuid = ?")) {
            statement.setString(1, account.getDiscordID());
            statement.setString(2, account.getUuid());
            statement.executeUpdate();
        }
    }

    private void replaceCode(Connection connection, SDLinkAccount account) throws SQLException {
        deleteCode(connection, account.getUuid());
        if (account.getVerifyCode() == null) return;
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO pending_verifications(account_uuid, code, expires_at) VALUES(?, ?, ?)")) {
            statement.setString(1, account.getUuid());
            statement.setString(2, account.getVerifyCode());
            statement.setLong(3, Instant.now().getEpochSecond() + CODE_LIFETIME_SECONDS);
            statement.executeUpdate();
        }
    }

    public synchronized void deleteAccount(String uuid) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM accounts WHERE uuid = ?")) {
            statement.setString(1, uuid);
            statement.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Failed to delete account", e); }
    }

    public synchronized String getOrCreateCode(String uuid) {
        return transaction("issue verification code", connection -> {
            long now = Instant.now().getEpochSecond();
            deleteExpiredCodes(connection, now);
            String currentCode = activeCode(connection, uuid, now);
            if (currentCode != null) return currentCode;
            deleteCode(connection, uuid);
            return insertGeneratedCode(connection, uuid, now + CODE_LIFETIME_SECONDS);
        });
    }

    private void deleteExpiredCodes(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM pending_verifications WHERE expires_at <= ?")) {
            statement.setLong(1, now);
            statement.executeUpdate();
        }
    }

    private String activeCode(Connection connection, String uuid, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT code FROM pending_verifications WHERE account_uuid = ? AND expires_at > ?")) {
            statement.setString(1, uuid);
            statement.setLong(2, now);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private void deleteCode(Connection connection, String uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM pending_verifications WHERE account_uuid = ?")) {
            statement.setString(1, uuid);
            statement.executeUpdate();
        }
    }

    private String insertGeneratedCode(Connection connection, String uuid, long expiresAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO pending_verifications(account_uuid, code, expires_at) VALUES(?, ?, ?)")) {
            int firstCode = random.nextInt(9000);
            for (int attempt = 0; attempt < 9000; attempt++) {
                String code = String.valueOf(1000 + (firstCode + attempt) % 9000);
                statement.setString(1, uuid);
                statement.setString(2, code);
                statement.setLong(3, expiresAt);
                if (statement.executeUpdate() == 1) return code;
            }
        }
        throw new IllegalStateException("No verification codes are available");
    }

    public synchronized SDLinkAccount findAccountByCode(String code) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT a.*, p.code AS verify_code FROM accounts a JOIN pending_verifications p ON p.account_uuid = a.uuid WHERE p.code = ? AND p.expires_at > ?")) {
            statement.setString(1, code);
            statement.setLong(2, Instant.now().getEpochSecond());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? accountFrom(result) : null; }
        } catch (SQLException e) { throw new IllegalStateException("Failed to find verification code", e); }
    }

    public synchronized LinkStatus linkAccount(String uuid, String discordId, String code, boolean allowMultiple) {
        return transaction("link account", connection -> {
            if (code != null && !codeIsValid(connection, uuid, code)) return LinkStatus.CODE_NOT_FOUND;
            if (code != null && !allowMultiple && discordLinked(connection, discordId)) return LinkStatus.ALREADY_VERIFIED;
            setDiscordLink(connection, uuid, discordId);
            deleteCode(connection, uuid);
            return LinkStatus.SUCCESS;
        });
    }

    private boolean codeIsValid(Connection connection, String uuid, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM pending_verifications WHERE account_uuid = ? AND code = ? AND expires_at > ?")) {
            statement.setString(1, uuid);
            statement.setString(2, code);
            statement.setLong(3, Instant.now().getEpochSecond());
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private boolean discordLinked(Connection connection, String discordId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM accounts WHERE discord_id = ? LIMIT 1")) {
            statement.setString(1, discordId);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private void setDiscordLink(Connection connection, String uuid, String discordId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE accounts SET discord_id = ? WHERE uuid = ?")) {
            statement.setString(1, discordId);
            statement.setString(2, uuid);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Account disappeared during verification");
        }
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
