package xyz.tbvns.database;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseService {
    private static final String DB_URL = "jdbc:sqlite:gitscord.db";

    public static DatabaseService instance;

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

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS repo_channel_links (
                    repo_full_name   TEXT PRIMARY KEY,
                    forum_channel_id TEXT NOT NULL,
                    repo_tag_id      TEXT NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS repo_issue_tag_map (
                    repo_full_name   TEXT NOT NULL,
                    issue_label      TEXT NOT NULL,
                    discord_tag_id   TEXT NOT NULL,
                    PRIMARY KEY (repo_full_name, issue_label),
                    FOREIGN KEY (repo_full_name) REFERENCES repo_channel_links(repo_full_name)
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_issue_tag_repo ON repo_issue_tag_map(repo_full_name)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS repo_issue_threads (
                    repo_full_name TEXT NOT NULL,
                    issue_number   INTEGER NOT NULL,
                    thread_id      TEXT NOT NULL,
                    is_pull_request INTEGER NOT NULL DEFAULT 0,
                    created_at     TEXT NOT NULL,
                    PRIMARY KEY (repo_full_name, issue_number)
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_thread_id ON repo_issue_threads(thread_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_forum_channel ON repo_channel_links(forum_channel_id)");
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

    public void upsertRepoChannelLink(String repoFullName, String forumChannelId, String repoTagId) throws SQLException {
        String sql = """
            INSERT INTO repo_channel_links (repo_full_name, forum_channel_id, repo_tag_id)
            VALUES (?, ?, ?)
            ON CONFLICT(repo_full_name) DO UPDATE SET
                forum_channel_id = excluded.forum_channel_id,
                repo_tag_id      = excluded.repo_tag_id
        """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoFullName);
            ps.setString(2, forumChannelId);
            ps.setString(3, repoTagId);
            ps.executeUpdate();
        }
    }

    public String getForumChannelForRepo(String repoFullName) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT forum_channel_id FROM repo_channel_links WHERE repo_full_name = ?")) {
            ps.setString(1, repoFullName);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("forum_channel_id") : null;
        }
    }

    public String getRepoTagId(String repoFullName) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT repo_tag_id FROM repo_channel_links WHERE repo_full_name = ?")) {
            ps.setString(1, repoFullName);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("repo_tag_id") : null;
        }
    }

    public void deleteRepoChannelLink(String repoFullName) throws SQLException {
        // Remove child rows first to respect the FK constraint
        deleteAllIssueTagsForRepo(repoFullName);
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM repo_channel_links WHERE repo_full_name = ?")) {
            ps.setString(1, repoFullName);
            ps.executeUpdate();
        }
    }

    public void upsertIssueTagMapping(String repoFullName, String issueLabel, String discordTagId) throws SQLException {
        String sql = """
            INSERT INTO repo_issue_tag_map (repo_full_name, issue_label, discord_tag_id)
            VALUES (?, ?, ?)
            ON CONFLICT(repo_full_name, issue_label) DO UPDATE SET
                discord_tag_id = excluded.discord_tag_id
        """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoFullName);
            ps.setString(2, issueLabel);
            ps.setString(3, discordTagId);
            ps.executeUpdate();
        }
    }

    public String getDiscordTagForIssueLabel(String repoFullName, String issueLabel) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT discord_tag_id FROM repo_issue_tag_map WHERE repo_full_name = ? AND issue_label = ?")) {
            ps.setString(1, repoFullName);
            ps.setString(2, issueLabel);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("discord_tag_id") : null;
        }
    }

    public java.util.Map<String, String> getIssueTagMappingsForRepo(String repoFullName) throws SQLException {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT issue_label, discord_tag_id FROM repo_issue_tag_map WHERE repo_full_name = ?")) {
            ps.setString(1, repoFullName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) map.put(rs.getString("issue_label"), rs.getString("discord_tag_id"));
        }
        return map;
    }

    public void deleteIssueTagMapping(String repoFullName, String issueLabel) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM repo_issue_tag_map WHERE repo_full_name = ? AND issue_label = ?")) {
            ps.setString(1, repoFullName);
            ps.setString(2, issueLabel);
            ps.executeUpdate();
        }
    }

    public void deleteAllIssueTagsForRepo(String repoFullName) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM repo_issue_tag_map WHERE repo_full_name = ?")) {
            ps.setString(1, repoFullName);
            ps.executeUpdate();
        }
    }

    public void upsertIssueThread(String repoFullName, int issueNumber, String threadId, boolean isPullRequest) throws SQLException {
        String sql = """
            INSERT INTO repo_issue_threads (repo_full_name, issue_number, thread_id, is_pull_request, created_at)
            VALUES (?, ?, ?, ?, datetime('now'))
            ON CONFLICT(repo_full_name, issue_number) DO UPDATE SET
                thread_id = excluded.thread_id,
                is_pull_request = excluded.is_pull_request
        """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, repoFullName);
            ps.setInt(2, issueNumber);
            ps.setString(3, threadId);
            ps.setInt(4, isPullRequest ? 1 : 0);
            ps.executeUpdate();
        }
    }

    /** Returns the Discord thread ID for a given repo+issue/PR number, or null if not tracked. */
    public String getThreadIdForIssue(String repoFullName, int issueNumber) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT thread_id FROM repo_issue_threads WHERE repo_full_name = ? AND issue_number = ?")) {
            ps.setString(1, repoFullName);
            ps.setInt(2, issueNumber);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("thread_id") : null;
        }
    }

    public void deleteIssueThread(String repoFullName, int issueNumber) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM repo_issue_threads WHERE repo_full_name = ? AND issue_number = ?")) {
            ps.setString(1, repoFullName);
            ps.setInt(2, issueNumber);
            ps.executeUpdate();
        }
    }

    public record ThreadIssueInfo(String repoFullName, int issueNumber, boolean isPullRequest) {}

    public String getRepoForForumChannel(String forumChannelId) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT repo_full_name FROM repo_channel_links WHERE forum_channel_id = ?")) {
            ps.setString(1, forumChannelId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("repo_full_name") : null;
        }
    }

    public ThreadIssueInfo getIssueFromThread(String threadId) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT repo_full_name, issue_number, is_pull_request FROM repo_issue_threads WHERE thread_id = ?")) {
            ps.setString(1, threadId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new ThreadIssueInfo(
                        rs.getString("repo_full_name"),
                        rs.getInt("issue_number"),
                        rs.getInt("is_pull_request") == 1
                );
            }
            return null;
        }
    }

    public String getRepoForTag(String discordTagId) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT repo_full_name FROM repo_channel_links WHERE repo_tag_id = ? LIMIT 1")) {
            ps.setString(1, discordTagId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("repo_full_name") : null;
        }
    }
}