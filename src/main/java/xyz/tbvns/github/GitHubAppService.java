package xyz.tbvns.github;

import jakarta.annotation.PostConstruct;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tbvns.database.DatabaseService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

@Service
public class GitHubAppService {
    public static GitHubAppService instance;
    private static final Logger log = LoggerFactory.getLogger(GitHubAppService.class);

    private final String appId = System.getenv("GITHUB_APP_ID");
    private final String privateKeyPath = System.getenv("GITHUB_APP_PRIVATE_KEY_PATH");

    @Autowired
    private DatabaseService db;


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

            db.upsertInstallation(installationId, login, userId, email, installedAt);
            log.info("Stored installation for {} (email: {})", login, email);

        } catch (Exception e) {
            log.error("Failed to process installation for login={}", login, e);
        }
    }

    public void handleInstallationDeleted(long installationId) {
        try {
            db.deleteInstallation(installationId);
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
            return db.getLoginByEmail(email);
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
        long installationId = db.getInstallationIdForLogin(login);
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
        List<String> logins = db.getGitHubLoginsForDiscord(discordUserId);
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
}