package xyz.tbvns.github;

import jakarta.annotation.PostConstruct;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import xyz.tbvns.database.DatabaseService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GitHubAppService {
    private static final Logger log = LoggerFactory.getLogger(GitHubAppService.class);

    private final String appId = System.getenv("GITHUB_APP_ID");
    private final String privateKeyPath = System.getenv("GITHUB_APP_PRIVATE_KEY_PATH");

    public static GitHubAppService instance;

    @PostConstruct
    public void postConstruct() {
        instance = this;
    }

    public void handleInstallationCreated(long installationId, String login, long userId, String installedAt) {
        try {
            // Authenticate as the app
            GitHub appGitHub = new GitHubBuilder()
                .withJwtToken(generateJwt())
                .build();

            // Get an installation token for this specific installation
            GHAppInstallation installation = appGitHub.getApp().getInstallationById(installationId);
            GHAppInstallationToken token = installation.createToken().create();

            // Use that token to fetch the user's profile (public email)
            GitHub installationGitHub = new GitHubBuilder()
                .withAppInstallationToken(token.getToken())
                .build();

            GHUser user = installationGitHub.getUser(login);
            String email = user.getEmail(); // public email, may be null

            DatabaseService.instance.upsertInstallation(installationId, login, userId, email, installedAt);
            log.info("Stored installation for {} (email: {})", login, email);

        } catch (Exception e) {
            log.error("Failed to process installation for login={}", login, e);
        }
    }

    public void handleInstallationDeleted(long installationId) {
        try {
            DatabaseService.instance.deleteInstallation(installationId);
            log.info("Removed installation {}", installationId);
        } catch (Exception e) {
            log.error("Failed to delete installation {}", installationId, e);
        }
    }

    /**
     * Main lookup: given a verified email, return the GitHub login.
     * Returns null if no matching installation found.
     */
    public String getGitHubLoginByEmail(String email) {
        try {
            return DatabaseService.instance.getLoginByEmail(email);
        } catch (Exception e) {
            log.error("DB lookup failed for email={}", email, e);
            return null;
        }
    }

    private String generateJwt() throws Exception {
        String privateKeyContent = new String(Files.readAllBytes(Paths.get(privateKeyPath)))
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(privateKeyContent);
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        long now = System.currentTimeMillis() / 1000;

        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540) + ",\"iss\":\"" + appId + "\"}").getBytes(StandardCharsets.UTF_8));

        String signingInput = header + "." + payload;

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());

        return signingInput + "." + signature;
    }

    /**
     * Returns all repos accessible via a user's GitHub App installation.
     * Works for both personal accounts and orgs.
     */
    public List<GHRepository> getReposForLogin(String login) throws Exception {
        long installationId = DatabaseService.instance.getInstallationIdForLogin(login);
        if (installationId == -1) throw new IllegalArgumentException("No installation found for: " + login);

        GitHub appGitHub = new GitHubBuilder()
                .withJwtToken(generateJwt())
                .build();

        GHAppInstallation installation = appGitHub.getApp().getInstallationById(installationId);
        GHAppInstallationToken token = installation.createToken().create();

        GitHub installationGitHub = new GitHubBuilder()
                .withAppInstallationToken(token.getToken())
                .build();

        // List repos owned by this user/org directly
        List<GHRepository> repos = new ArrayList<>();
        installationGitHub.getUser(login).listRepositories().forEach(repos::add);
        return repos;
    }

    /**
     * Returns repos for all GitHub accounts linked to a Discord user.
     * Map key = github login, value = their repos.
     */
    public Map<String, List<GHRepository>> getReposForDiscordUser(String discordUserId) throws Exception {
        List<String> logins = DatabaseService.instance.getGitHubLoginsForDiscord(discordUserId);
        Map<String, List<GHRepository>> result = new LinkedHashMap<>();
        for (String login : logins) {
            try {
                result.put(login, getReposForLogin(login));
            } catch (Exception e) {
                log.warn("Could not fetch repos for {}: {}", login, e.getMessage());
            }
        }
        return result;
    }

    private GitHub getGitHubForLogin(String login) throws Exception {
        long installationId = DatabaseService.instance.getInstallationIdForLogin(login);
        if (installationId == -1) {
            throw new IllegalArgumentException("No installation found for login: " + login);
        }
        GitHub appGitHub = new GitHubBuilder()
                .withJwtToken(generateJwt())
                .build();
        GHAppInstallation installation = appGitHub.getApp().getInstallationById(installationId);
        GHAppInstallationToken token = installation.createToken().create();
        return new GitHubBuilder()
                .withAppInstallationToken(token.getToken())
                .build();
    }

    private GHRepository getRepositoryForLogin(String login, String repoFullName) throws Exception {
        return getGitHubForLogin(login).getRepository(repoFullName);
    }

    public void addCommentToIssue(String repoFullName, int issueNumber, String message) {
        try {
            String owner = getOwnerFromRepo(repoFullName);
            GHRepository repo = getRepositoryForLogin(owner, repoFullName);
            repo.getIssue(issueNumber).comment(message);
            log.info("Added comment to {}/#{}", repoFullName, issueNumber);
        } catch (Exception e) {
            log.error("Failed to comment on {}/#{}", repoFullName, issueNumber, e);
            throw new RuntimeException("Could not add comment", e);
        }
    }

    public void setIssueLabels(String repoFullName, int issueNumber, List<String> labels) {
        try {
            String owner = getOwnerFromRepo(repoFullName);
            GHRepository repo = getRepositoryForLogin(owner, repoFullName);
            repo.getIssue(issueNumber).setLabels(labels.toArray(new String[0]));
            log.info("Set labels on {}/#{} to {}", repoFullName, issueNumber, labels);
        } catch (Exception e) {
            log.error("Failed to set labels on {}/#{}", repoFullName, issueNumber, e);
            throw new RuntimeException("Could not set labels", e);
        }
    }

    public List<String> getIssueLabels(String repoFullName, int issueNumber) {
        try {
            String owner = getOwnerFromRepo(repoFullName);
            GHRepository repo = getRepositoryForLogin(owner, repoFullName);
            List<String> labels = repo.getIssue(issueNumber)
                    .getLabels()
                    .stream()
                    .map(GHLabel::getName)
                    .collect(Collectors.toList());
            log.info("Retrieved labels from {}/#{}: {}", repoFullName, issueNumber, labels);
            return labels;
        } catch (Exception e) {
            log.error("Failed to get labels from {}/#{}", repoFullName, issueNumber, e);
            throw new RuntimeException("Could not get labels", e);
        }
    }

    public void updateIssueTitle(String repoFullName, int issueNumber, String newTitle) {
        try {
            String owner = getOwnerFromRepo(repoFullName);
            GHRepository repo = getRepositoryForLogin(owner, repoFullName);
            repo.getIssue(issueNumber).setTitle(newTitle);
            log.info("Updated title of {}/#{} to '{}'", repoFullName, issueNumber, newTitle);
        } catch (Exception e) {
            log.error("Failed to update title on {}/#{}", repoFullName, issueNumber, e);
            throw new RuntimeException("Could not update title", e);
        }
    }

    private String getOwnerFromRepo(String repoFullName) {
        return repoFullName.split("/")[0];
    }

    public GHIssue createIssue(String repoFullName, String title, String body) {
        try {
            String owner = getOwnerFromRepo(repoFullName);
            GHRepository repo = getRepositoryForLogin(owner, repoFullName);
            GHIssue issue = repo.createIssue(title)
                    .body(body)
                    .create();
            log.info("Created issue #{} on {}: '{}'", issue.getNumber(), repoFullName, title);
            return issue;
        } catch (Exception e) {
            log.error("Failed to create issue on {}", repoFullName, e);
            throw new RuntimeException("Could not create issue", e);
        }
    }
}