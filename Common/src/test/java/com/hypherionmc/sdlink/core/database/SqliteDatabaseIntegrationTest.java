package com.hypherionmc.sdlink.core.database;

import com.google.gson.Gson;
import com.hypherionmc.sdlink.core.discord.VerificationRateLimiter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/** Standalone regression check because this project does not use a test framework. */
public final class SqliteDatabaseIntegrationTest {

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("sdlink-storage-test-");
        try {
            migrationAndCodes(root.resolve("normal"));
            failedMigrationCanRetry(root.resolve("retry"));
            String user = "test-" + UUID.randomUUID();
            for (int i = 0; i < 5; i++) check(VerificationRateLimiter.INSTANCE.allowAttempt(user), "Allowed attempt " + i);
            check(!VerificationRateLimiter.INSTANCE.allowAttempt(user), "Sixth attempt must be limited");
            VerificationRateLimiter.INSTANCE.clear(user);
            check(VerificationRateLimiter.INSTANCE.allowAttempt(user), "Success must clear the limit");
            System.out.println("SQLite storage integration checks passed");
        } finally {
            try (var files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (Exception e) { throw new IllegalStateException("Failed to clean test data", e); }
                });
            }
        }
    }

    private static void migrationAndCodes(Path folder) throws Exception {
        Files.createDirectories(folder);
        Gson gson = new Gson();
        StringBuilder legacy = new StringBuilder("{\"schemaVersion\":\"1.0\"}\n");
        String firstId = null;
        String secondId = null;
        String thirdId = null;
        for (int i = 0; i < 5000; i++) {
            SDLinkAccount account = new SDLinkAccount();
            account.setUuid(UUID.nameUUIDFromBytes(("player-" + i).getBytes(StandardCharsets.UTF_8)).toString());
            account.setUsername("player" + i);
            account.setInGameName("Player " + i);
            if (i == 0) {
                firstId = account.getUuid();
                account.setVerifyCode("1234");
            }
            if (i == 1) {
                secondId = account.getUuid();
                account.setDiscordID("existing-link");
            }
            if (i == 2) thirdId = account.getUuid();
            legacy.append(gson.toJson(account)).append('\n');
        }
        Path accountsFile = folder.resolve("verifiedaccounts.json");
        Files.writeString(accountsFile, legacy);
        String hidden = "{\"schemaVersion\":\"1.0\"}\n" + gson.toJson(HiddenPlayers.of("hidden-id", "Hidden", "discord")) + "\n";
        Files.writeString(folder.resolve("hiddenplayers.json"), hidden);

        SqliteDatabase db = new SqliteDatabase(folder.toString());
        db.initialize();
        check(db.allAccounts().size() == 5000, "All account registrations must migrate");
        check("existing-link".equals(db.findAccount(secondId).getDiscordID()), "Existing Discord link must migrate");
        check(db.findHiddenPlayer("hidden-id") != null, "Hidden player must migrate");
        check(db.findAccountByCode("1234") == null, "Undated old code must not migrate");
        check(Files.readString(accountsFile).equals(legacy.toString()), "Legacy backup must remain untouched");

        String code = db.getOrCreateCode(firstId);
        check(code.matches("[1-9][0-9]{3}"), "Code must remain four digits");
        check(code.equals(db.getOrCreateCode(firstId)), "Active code must be reused");
        check(db.findAccountByCode(code).getUuid().equals(firstId), "Code lookup must find its account");
        String otherCode = db.getOrCreateCode(thirdId);
        check(!code.equals(otherCode), "Pending codes must be unique");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("sdlink.db"));
             PreparedStatement statement = connection.prepareStatement("SELECT expires_at FROM pending_verifications WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                check(result.next(), "Code must be stored");
                long remaining = result.getLong(1) - Instant.now().getEpochSecond();
                check(remaining > 3500 && remaining <= 3600, "Code must expire in one hour");
            }
        }

        check(db.linkAccount(firstId, "new-link", code, false) == SqliteDatabase.LinkStatus.SUCCESS, "Valid code must link");
        check(db.findAccountByCode(code) == null, "Consumed code must be removed");
        check(db.linkAccount(thirdId, "new-link", otherCode, false) == SqliteDatabase.LinkStatus.ALREADY_VERIFIED,
                "Multiple-account rule must still be enforced");
        check(db.findAccountByCode(otherCode) != null, "Rejected link must leave its code available");
        check(db.linkAccount(secondId, "new-link", null, true) == SqliteDatabase.LinkStatus.SUCCESS, "Staff link must work");
        SDLinkAccount stale = db.findAccount(firstId);
        stale.setDiscordID(null);
        stale.setInGameName("New display name");
        db.upsertAccount(stale);
        check("new-link".equals(db.findAccount(firstId).getDiscordID()), "Name update must not erase Discord link");

        db.unlinkAccounts("new-link");
        check(db.findAccount(firstId).getDiscordID() == null, "Unlink must clear first account");
        check(db.findAccount(secondId).getDiscordID() == null, "Unlink must clear all linked accounts");
        String expiring = db.getOrCreateCode(firstId);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("sdlink.db"));
             PreparedStatement statement = connection.prepareStatement("UPDATE pending_verifications SET expires_at = ? WHERE code = ?")) {
            statement.setLong(1, Instant.now().getEpochSecond() - 1);
            statement.setString(2, expiring);
            statement.executeUpdate();
        }
        check(db.findAccountByCode(expiring) == null, "Expired code must fail before restart");
        check(db.linkAccount(firstId, "expired-attempt", expiring, false) == SqliteDatabase.LinkStatus.CODE_NOT_FOUND,
                "Expired code must not be redeemable");
        String renewed = db.getOrCreateCode(firstId);
        check(db.findAccountByCode(renewed) != null, "Expired code must be replaced on request");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("sdlink.db"));
             PreparedStatement statement = connection.prepareStatement("UPDATE pending_verifications SET expires_at = ? WHERE account_uuid = ?")) {
            statement.setLong(1, Instant.now().getEpochSecond() - 1);
            statement.setString(2, firstId);
            statement.executeUpdate();
        }
        db.initialize();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("sdlink.db"));
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM pending_verifications");
             ResultSet result = statement.executeQuery()) {
            check(result.next() && result.getInt(1) == 1, "Startup must remove expired codes and retain live codes");
        }
        check(db.allAccounts().size() == 5000, "Restart must not reimport legacy JSON");
    }

    private static void failedMigrationCanRetry(Path folder) throws Exception {
        Files.createDirectories(folder);
        SDLinkAccount account = new SDLinkAccount();
        account.setUuid(UUID.randomUUID().toString());
        account.setUsername("retry-player");
        Files.writeString(folder.resolve("verifiedaccounts.json"), "{\"schemaVersion\":\"1.0\"}\n" + new Gson().toJson(account) + "\n");
        Path hiddenFile = folder.resolve("hiddenplayers.json");
        Files.writeString(hiddenFile, "malformed legacy file");
        SqliteDatabase db = new SqliteDatabase(folder.toString());
        boolean failed = false;
        try { db.initialize(); } catch (IllegalStateException expected) { failed = true; }
        check(failed, "Malformed migration must fail");
        Files.writeString(hiddenFile, "{\"schemaVersion\":\"1.0\"}\n");
        db.initialize();
        check(db.allAccounts().size() == 1, "Failed migration must retry without duplicate or lost rows");
    }
}
