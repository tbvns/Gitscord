package xyz.tbvns.database;

import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Service
public class DatabaseService {
    private static final String DB_URL = "jdbc:sqlite:gitscord.db";

    public static DatabaseService instance;

    @PostConstruct
    public void init() throws SQLException {
        instance = this;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS github_installations (
                    installation_id INTEGER PRIMARY KEY,
                    github_login    TEXT NOT NULL,
                    github_user_id  INTEGER NOT NULL,
                    github_email    TEXT,
                    installed_at    TEXT NOT NULL
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_email ON github_installations(github_email)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_login ON github_installations(github_login)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS discord_github_links (
                    discord_user_id TEXT NOT NULL,
                    github_login    TEXT NOT NULL,
                    github_user_id  INTEGER NOT NULL,
                    linked_at       TEXT NOT NULL,
                    PRIMARY KEY (discord_user_id, github_login),
                    FOREIGN KEY (github_login) REFERENCES github_installations(github_login)
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_discord ON discord_github_links(discord_user_id)");
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    public void upsertInstallation(long installationId, String login, long userId, String email, String installedAt) throws SQLException {
        String sql = """
            INSERT INTO github_installations (installation_id, github_login, github_user_id, github_email, installed_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(installation_id) DO UPDATE SET
                github_login = excluded.github_login,
                github_email = excluded.github_email
        """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, installationId);
            ps.setString(2, login);
            ps.setLong(3, userId);
            ps.setString(4, email);
            ps.setString(5, installedAt);
            ps.executeUpdate();
        }
    }

    public void deleteInstallation(long installationId) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM github_installations WHERE installation_id = ?")) {
            ps.setLong(1, installationId);
            ps.executeUpdate();
        }
    }

    /** Returns the GitHub login for a given email, or null if not found. */
    public String getLoginByEmail(String email) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT github_login FROM github_installations WHERE LOWER(github_email) = LOWER(?)")) {
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("github_login") : null;
        }
    }

    /** Returns the installation record for a given login, or null. */
    public ResultSet getInstallationByLogin(String login) throws SQLException {
        Connection conn = getConnection();
        PreparedStatement ps = conn.prepareStatement(
            "SELECT * FROM github_installations WHERE LOWER(github_login) = LOWER(?)");
        ps.setString(1, login);
        return ps.executeQuery(); // caller must close
    }

    public void linkDiscordToGitHub(String discordUserId, String githubLogin, long githubUserId) throws SQLException {
        String sql = """
        INSERT OR IGNORE INTO discord_github_links (discord_user_id, github_login, github_user_id, linked_at)
        VALUES (?, ?, ?, datetime('now'))
    """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, discordUserId);
            ps.setString(2, githubLogin);
            ps.setLong(3, githubUserId);
            ps.executeUpdate();
        }
    }

    public void unlinkDiscordFromGitHub(String discordUserId, String githubLogin) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM discord_github_links WHERE discord_user_id = ? AND github_login = ?")) {
            ps.setString(1, discordUserId);
            ps.setString(2, githubLogin);
            ps.executeUpdate();
        }
    }

    /** All GitHub logins linked to a Discord user */
    public List<String> getGitHubLoginsForDiscord(String discordUserId) throws SQLException {
        List<String> logins = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT github_login FROM discord_github_links WHERE discord_user_id = ?")) {
            ps.setString(1, discordUserId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) logins.add(rs.getString("github_login"));
        }
        return logins;
    }

    /** Which Discord users are linked to a given GitHub login */
    public List<String> getDiscordUsersForGitHub(String githubLogin) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT discord_user_id FROM discord_github_links WHERE github_login = ?")) {
            ps.setString(1, githubLogin);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) ids.add(rs.getString("discord_user_id"));
        }
        return ids;
    }

    public long getInstallationIdForLogin(String login) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT installation_id FROM github_installations WHERE LOWER(github_login) = LOWER(?)")) {
            ps.setString(1, login);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong("installation_id") : -1;
        }
    }

    public long getGitHubUserIdForLogin(String login) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT github_user_id FROM github_installations WHERE LOWER(github_login) = LOWER(?)")) {
            ps.setString(1, login);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong("github_user_id") : -1;
        }
    }
}