package xyz.tbvns.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/github-webhook")
public class GitHubWebhookController {
    public static final Map<Integer, Long> issueToThread = new ConcurrentHashMap<>(); // kept for compatibility
    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

    @Autowired
    private JDA jda;

    @Autowired
    private GitHubAppService gitHubAppService;

    @Autowired
    private GitHubToDiscordBridge bridge;   // <-- injected bridge

    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping
    public ResponseEntity<String> handleWebhook(
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String rawBody) {

        System.out.println(rawBody);

        try {
            JsonNode root = mapper.readTree(rawBody);
            if ("installation".equals(event)) {
                String action = root.path("action").asText();
                JsonNode installation = root.path("installation");
                JsonNode sender = root.path("sender");

                long installationId = installation.path("id").asLong();
                String login = sender.path("login").asText();
                long userId = sender.path("id").asLong();
                String createdAt = installation.path("created_at").asText();

                if ("created".equals(action)) {
                    gitHubAppService.handleInstallationCreated(installationId, login, userId, createdAt);
                } else if ("deleted".equals(action)) {
                    gitHubAppService.handleInstallationDeleted(installationId);
                }
                return ResponseEntity.ok("ok");
            }

            switch (event) {
                case "issues":
                    bridge.handleIssueEvent(root);
                    break;
                case "issue_comment":
                    bridge.handleIssueCommentEvent(root);
                    break;
                case "pull_request":
                    bridge.handlePullRequestEvent(root);
                    break;
                case "ping":
                    log.debug("Received ping event");
                    break;
                default:
                    log.debug("Unhandled event type: {}", event);
            }

        } catch (Exception e) {
            log.error("Webhook processing failed for event {}: {}", event, e.getMessage(), e);
        }

        return ResponseEntity.ok("ok");
    }
}