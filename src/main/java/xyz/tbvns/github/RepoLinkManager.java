package xyz.tbvns.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.forums.ForumTag;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.SubscribeEvent;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tbvns.database.DatabaseService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RepoLinkManager {
    private static final Logger log = LoggerFactory.getLogger(RepoLinkManager.class);
    private static final int MAX_ROWS = 5;
    private static final int REPO_TAG_ROWS = 1;
    private static final int LABELS_STEP_0 = MAX_ROWS - REPO_TAG_ROWS; // 4
    private static final int LABELS_PER_STEP = MAX_ROWS; // 5
    private static final String MODAL_PREFIX = "repo_link_";
    private static final String CONTINUE_BTN_PREFIX = "repo_link_continue_";
    private static final String CREATE_TAG_VALUE = "__create_new_tag__";
    private static final String IGNORE_VALUE = "__ignore__";
    private static final int DISCORD_TAG_NAME_MAX_LEN = 20;
    private static final int MAX_FORUM_TAGS = 20;
    private static final int SELECT_MAX_OPTIONS = 25;

    private final DatabaseService databaseService = DatabaseService.instance;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Map<String, LinkSession> sessions = new ConcurrentHashMap<>();

    static {
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "repo-link-session-cleanup");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(
                () -> sessions.values().removeIf(LinkSession::isExpired),
                5, 5, TimeUnit.MINUTES
        );
    }

    private record ForumTagOption(String id, String name) {}

    private static final class LinkSession {
        final String repoFullName;
        final String forumChannelId;
        final List<String> githubLabels;
        final List<ForumTagOption> discordTags;
        final Map<String, String> collected = new LinkedHashMap<>();
        final Instant createdAt = Instant.now();
        int currentStep = 0;

        LinkSession(String repoFullName, String forumChannelId,
                    List<String> githubLabels, List<ForumTagOption> discordTags) {
            this.repoFullName = repoFullName;
            this.forumChannelId = forumChannelId;
            this.githubLabels = githubLabels;
            this.discordTags = discordTags;
        }

        boolean isExpired() {
            return Instant.now().isAfter(createdAt.plusSeconds(600));
        }
    }

    public void linkChannelToRepo(SlashCommandInteractionEvent event,
                                  String forumChannelId,
                                  String repoFullName) {
        List<String> labels = fetchGitHubLabels(repoFullName);

        List<ForumTagOption> discordTags = new ArrayList<>();
        ForumChannel channel = event.getGuild().getForumChannelById(forumChannelId);
        if (channel != null) {
            for (ForumTag tag : channel.getAvailableTags()) {
                discordTags.add(new ForumTagOption(tag.getId(), tag.getName()));
            }
        }

        LinkSession session = new LinkSession(repoFullName, forumChannelId, labels, discordTags);
        sessions.put(event.getUser().getId(), session);

        event.replyModal(buildModal(session)).queue();
    }

    @SubscribeEvent
    public void handleModalSubmit(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith(MODAL_PREFIX)) return;

        String userId = event.getUser().getId();
        LinkSession session = sessions.get(userId);

        if (session == null) {
            event.reply("⚠️ Session expired — please run the command again.")
                    .setEphemeral(true).queue();
            return;
        }

        collectValues(event, session);

        boolean done = labelsProcessedAfterStep(session.currentStep) >= session.githubLabels.size();

        if (done) {
            sessions.remove(userId);
            persist(session, event);
        } else {
            session.currentStep++;
            int total = totalSteps(session.githubLabels.size());
            int next = session.currentStep + 1;

            event.reply(new MessageCreateBuilder()
                    .useComponentsV2(true)
                    .addComponents(
                            Container.of(
                                    TextDisplay.of("## Step " + next + "/" + total + " saved"),
                                    TextDisplay.of("Click **Continue** to configure the next batch of labels."),
                                    ActionRow.of(Button.primary(CONTINUE_BTN_PREFIX + userId, "Continue →"))
                            )
                    )
                    .build()
            ).setEphemeral(true).queue();
        }
    }

    @SubscribeEvent
    public void handleContinueButton(ButtonInteractionEvent event) {
        if (!event.getComponentId().startsWith(CONTINUE_BTN_PREFIX)) return;

        String userId = event.getUser().getId();
        LinkSession session = sessions.get(userId);

        if (session == null) {
            event.reply("⚠️ Session expired — please run the command again.")
                    .setEphemeral(true).queue();
            return;
        }

        event.replyModal(buildModal(session)).queue();
    }

    private Modal buildModal(LinkSession session) {
        int step = session.currentStep;
        int total = totalSteps(session.githubLabels.size());
        String title = buildTitle(session.repoFullName, step, total);

        List<net.dv8tion.jda.api.components.ModalTopLevelComponent> rows = new ArrayList<>();

        if (step == 0) {
            rows.add(buildRepoTagSelect(session));

            int end = Math.min(LABELS_STEP_0, session.githubLabels.size());
            for (int i = 0; i < end; i++) {
                rows.add(labelInput(session.githubLabels.get(i), i, session.discordTags));
            }
        } else {
            int start = labelStartForStep(step);
            int end = Math.min(start + LABELS_PER_STEP, session.githubLabels.size());
            for (int i = start; i < end; i++) {
                rows.add(labelInput(session.githubLabels.get(i), i, session.discordTags));
            }
        }

        return Modal.create(MODAL_PREFIX + step, title)
                .addComponents(rows)
                .build();
    }

    /**
     * Builds the repo‑tag select with “Ignore” (default), “Create one”, and existing tags.
     */
    private Label buildRepoTagSelect(LinkSession session) {
        List<ForumTagOption> allTags = session.discordTags;
        // Account for Ignore + Create One
        int maxDisplay = SELECT_MAX_OPTIONS - 2;
        List<ForumTagOption> displayTags = allTags.size() > maxDisplay
                ? allTags.subList(0, maxDisplay)
                : allTags;

        StringSelectMenu.Builder menu = StringSelectMenu.create("f_repo_tag")
                .setRequiredRange(0, 1)   // optional select
                .setRequired(false)
                .setDefaultValues(IGNORE_VALUE)              // <<< DEFAULT: IGNORE
                .addOption("⏭️ Ignore (no repo tag)", IGNORE_VALUE,
                        "Don't set a repo tag for this channel")
                .addOption("🆕 Create one", CREATE_TAG_VALUE,
                        "Creates a new forum tag named after the repository");

        for (ForumTagOption tag : displayTags) {
            menu.addOption(truncate(tag.name(), 100), tag.id());
        }

        return Label.of("Repo tag (optional)", menu.build());
    }

    /**
     * Builds a label select with “Ignore” (default), “Create one”, and existing tags.
     * “Create one” is always present, even if the channel has no tags.
     */
    private Label labelInput(String label, int index, List<ForumTagOption> discordTags) {
        // Reserve space for Ignore + Create One
        int tagBudget = SELECT_MAX_OPTIONS - 2;
        List<ForumTagOption> visibleTags = discordTags.size() > tagBudget
                ? discordTags.subList(0, tagBudget)
                : discordTags;

        StringSelectMenu.Builder menu = StringSelectMenu.create("f_lbl_" + index)
                .setRequiredRange(1, 1)
                .setDefaultValues(IGNORE_VALUE)                     // <<< DEFAULT: IGNORE
                .addOption("⏭️ Ignore this label", IGNORE_VALUE,
                        "Don't map this GitHub label to any Discord tag");

        // Always show “Create one”
        menu.addOption("🆕 Create one", CREATE_TAG_VALUE,
                truncate("Creates a new forum tag named \"" + label + "\"", 50));

        for (ForumTagOption tag : visibleTags) {
            menu.addOption(truncate(tag.name(), 100), tag.id());
        }

        return Label.of(truncate("GH label: " + label, 45), menu.build());
    }

    private String buildTitle(String repoFullName, int step, int total) {
        String base = truncate(repoFullName, 28);
        String steps = total > 1 ? " (" + (step + 1) + "/" + total + ")" : "";
        return truncate("Link: " + base + steps, 45);
    }

    private void collectValues(ModalInteractionEvent event, LinkSession session) {
        int step = session.currentStep;

        if (step == 0) {
            // Repo tag – will be IGNORE_VALUE if the user didn't change it
            List<String> repoTagValues = event.getValue("f_repo_tag").getAsStringList();
            session.collected.put("__repo_tag__", repoTagValues.isEmpty() ? "" : repoTagValues.get(0));

            int end = Math.min(LABELS_STEP_0, session.githubLabels.size());
            for (int i = 0; i < end; i++) {
                String value = selectedValue(event, i);
                if (value != null && !value.equals(IGNORE_VALUE)) {
                    session.collected.put(session.githubLabels.get(i), value);
                }
                // else: ignore – do not store anything (persist will skip it)
            }
        } else {
            int start = labelStartForStep(step);
            int end = Math.min(start + LABELS_PER_STEP, session.githubLabels.size());
            for (int i = start; i < end; i++) {
                String value = selectedValue(event, i);
                if (value != null && !value.equals(IGNORE_VALUE)) {
                    session.collected.put(session.githubLabels.get(i), value);
                }
            }
        }
    }

    private String selectedValue(ModalInteractionEvent event, int labelIndex) {
        List<String> values = event.getValue("f_lbl_" + labelIndex).getAsStringList();
        if (values.isEmpty()) {
            return IGNORE_VALUE; // they left it empty -> ignore
        }
        return values.get(0);
    }

    private void persist(LinkSession session, ModalInteractionEvent event) {
        try {
            // ---- Repo tag ----
            String repoTagValue = session.collected.getOrDefault("__repo_tag__", "").trim();
            String repoTagId = "";
            if (!repoTagValue.isEmpty()
                    && !repoTagValue.equals(IGNORE_VALUE)
                    && !CREATE_TAG_VALUE.equals(repoTagValue)
                    && isSnowflake(repoTagValue)) {
                repoTagId = repoTagValue; // selected existing tag
            } else if (CREATE_TAG_VALUE.equals(repoTagValue)) {
                repoTagId = createOrGetForumTag(session, event); // create or reuse
            }
            // else keep empty (IGNORE_VALUE or empty → ignored)

            databaseService.upsertRepoChannelLink(
                    session.repoFullName, session.forumChannelId, repoTagId);

            // ---- Labels ----
            Map<String, String> labelToExistingTag = new LinkedHashMap<>();
            Map<String, String> labelsToCreate = new LinkedHashMap<>();

            for (Map.Entry<String, String> entry : session.collected.entrySet()) {
                if (entry.getKey().equals("__repo_tag__")) continue;
                String label = entry.getKey();
                String value = entry.getValue();
                if (value == null || value.isEmpty() || value.equals(IGNORE_VALUE)) {
                    continue; // skipped – ignore is never saved
                }
                if (CREATE_TAG_VALUE.equals(value)) {
                    labelsToCreate.put(label, truncate(label, DISCORD_TAG_NAME_MAX_LEN));
                } else if (isSnowflake(value)) {
                    labelToExistingTag.put(label, value);
                }
            }

            // Save explicit mappings (existing tags)
            for (Map.Entry<String, String> entry : labelToExistingTag.entrySet()) {
                databaseService.upsertIssueTagMapping(session.repoFullName, entry.getKey(), entry.getValue());
            }

            // Handle creation (with reuse and deduplication)
            Map<String, String> createdOrReused = createOrReuseForumTags(session, event, labelsToCreate);

            // Save created/reused mappings
            for (Map.Entry<String, String> entry : createdOrReused.entrySet()) {
                databaseService.upsertIssueTagMapping(session.repoFullName, entry.getKey(), entry.getValue());
            }

            int totalMapped = labelToExistingTag.size() + createdOrReused.size();

            String repoTagSummary = repoTagId.isBlank()
                    ? "*(none set - ignored)*"
                    : "`" + repoTagId + "`";

            event.reply(
                    "✅ **" + session.repoFullName + "** linked to <#" + session.forumChannelId + ">\n" +
                            "• Repo tag: " + repoTagSummary + "\n" +
                            "• Issue-label mappings saved: **" + totalMapped + "**" +
                            (createdOrReused.isEmpty() ? "" : "\n• Tags used/created: **" + createdOrReused.size() + "**")
            ).setEphemeral(true).queue();

        } catch (Exception ex) {
            log.error("Failed to persist link for {}", session.repoFullName, ex);
            event.reply("❌ Failed to save configuration: " + ex.getMessage())
                    .setEphemeral(true).queue();
        }
    }

    private String createOrGetForumTag(LinkSession session, ModalInteractionEvent event) {
        String botToken = System.getenv("discord_token");
        if (botToken == null || botToken.isEmpty()) {
            log.error("Discord bot token not available");
            return "";
        }

        String channelId = session.forumChannelId;
        String tagName = truncate(session.repoFullName, DISCORD_TAG_NAME_MAX_LEN);

        // Fetch current tags from Discord REST
        Map<String, String> currentTags = fetchTagsFromDiscord(channelId, botToken);

        // If tag already exists, return its ID
        if (currentTags.containsKey(tagName)) {
            return currentTags.get(tagName);
        }

        // Check capacity
        if (currentTags.size() >= MAX_FORUM_TAGS) {
            log.warn("Forum {} full, cannot create repo tag '{}'", channelId, tagName);
            return "";
        }

        // Build payload: list of tag objects (existing + new)
        List<Map<String, String>> tagsPayload = new ArrayList<>();
        for (Map.Entry<String, String> entry : currentTags.entrySet()) {
            tagsPayload.add(Map.of("id", entry.getValue(), "name", entry.getKey()));
        }
        tagsPayload.add(Map.of("name", tagName)); // new tag has no id

        // Send PATCH request
        updateChannelTagsViaRest(channelId, botToken, tagsPayload);

        // Re-fetch to get the new tag ID
        Map<String, String> newTags = fetchTagsFromDiscord(channelId, botToken);
        if (newTags.containsKey(tagName)) {
            return newTags.get(tagName);
        }

        log.warn("Tag '{}' was created but not found after re-fetch", tagName);
        return "";
    }

    private Map<String, String> createOrReuseForumTags(LinkSession session, ModalInteractionEvent event,
                                                       Map<String, String> labelToTagName) {
        if (labelToTagName.isEmpty()) return Map.of();

        String botToken = System.getenv("discord_token");
        if (botToken == null || botToken.isEmpty()) {
            log.error("Discord bot token not available");
            return Map.of();
        }

        String channelId = session.forumChannelId;
        Map<String, String> currentTags = fetchTagsFromDiscord(channelId, botToken);

        Map<String, String> result = new LinkedHashMap<>();

        // Separate labels into existing tags and those needing creation
        Map<String, String> labelsNeedingCreation = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : labelToTagName.entrySet()) {
            String label = entry.getKey();
            String desiredTagName = entry.getValue();
            if (currentTags.containsKey(desiredTagName)) {
                result.put(label, currentTags.get(desiredTagName));
            } else {
                labelsNeedingCreation.put(label, desiredTagName);
            }
        }

        if (labelsNeedingCreation.isEmpty()) {
            return result;
        }

        // Deduplicate tag names to avoid duplicates
        Map<String, List<String>> tagNameToLabels = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : labelsNeedingCreation.entrySet()) {
            tagNameToLabels.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        // Check capacity
        int capacity = MAX_FORUM_TAGS - currentTags.size();
        if (capacity <= 0) {
            log.warn("Forum {} full, cannot create label tags", channelId);
            return result;
        }

        // Build payload: existing tags + new tags (up to capacity)
        List<Map<String, String>> tagsPayload = new ArrayList<>();
        for (Map.Entry<String, String> entry : currentTags.entrySet()) {
            tagsPayload.add(Map.of("id", entry.getValue(), "name", entry.getKey()));
        }

        int createdCount = 0;
        Map<String, String> createdTagNames = new LinkedHashMap<>(); // tagName -> (will be filled later)
        for (Map.Entry<String, List<String>> entry : tagNameToLabels.entrySet()) {
            if (createdCount >= capacity) break;
            String tagName = entry.getKey();
            tagsPayload.add(Map.of("name", tagName));
            createdTagNames.put(tagName, null);
            createdCount++;
        }

        if (createdTagNames.isEmpty()) {
            return result;
        }

        // Send PATCH request
        updateChannelTagsViaRest(channelId, botToken, tagsPayload);

        // Re-fetch to get new IDs
        Map<String, String> newTags = fetchTagsFromDiscord(channelId, botToken);
        if (newTags.isEmpty()) {
            log.warn("Failed to fetch tags after creation");
            return result;
        }

        // Map each created tag name to its new ID
        Map<String, String> createdTagNameToId = new LinkedHashMap<>();
        for (String tagName : createdTagNames.keySet()) {
            if (newTags.containsKey(tagName)) {
                createdTagNameToId.put(tagName, newTags.get(tagName));
            }
        }

        // Fill result for labels that were created
        for (Map.Entry<String, List<String>> entry : tagNameToLabels.entrySet()) {
            String tagName = entry.getKey();
            String tagId = createdTagNameToId.get(tagName);
            if (tagId != null) {
                for (String label : entry.getValue()) {
                    result.put(label, tagId);
                }
            }
        }

        return result;
    }

    private void updateChannelTagsViaRest(String channelId, String botToken, List<Map<String, String>> tagsPayload) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String jsonBody = mapper.writeValueAsString(Map.of("available_tags", tagsPayload));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/channels/" + channelId))
                    .header("Authorization", "Bot " + botToken)
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Failed to update tags: {} - {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Error updating forum tags via REST", e);
        }
    }

    /**
     * Fetches available forum tags directly from Discord's REST API, bypassing JDA's cache.
     * Returns a map of tag name -> tag id.
     */
    private Map<String, String> fetchTagsFromDiscord(String channelId, String botToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/channels/" + channelId))
                    .header("Authorization", "Bot " + botToken)
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            JsonNode tagsNode = root.path("available_tags");

            Map<String, String> nameToId = new LinkedHashMap<>();
            if (tagsNode.isArray()) {
                for (JsonNode tag : tagsNode) {
                    nameToId.put(tag.path("name").asText(), tag.path("id").asText());
                }
            }
            return nameToId;
        } catch (Exception e) {
            log.error("Failed to fetch channel tags from Discord REST for channel {}", channelId, e);
            return Map.of();
        }
    }

    // ---------- Utility ----------
    private boolean isSnowflake(String id) {
        if (id == null || id.isBlank()) return false;
        try {
            Long.parseLong(id);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private List<String> fetchGitHubLabels(String repoFullName) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(
                            "https://api.github.com/repos/" + repoFullName + "/labels?per_page=100"))
                    .header("Accept", "application/vnd.github+json");

            HttpResponse<String> response =
                    httpClient.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());

            JsonNode root = mapper.readTree(response.body());
            List<String> labels = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if (node.has("name")) labels.add(node.get("name").asText());
                }
            }
            return labels;
        } catch (Exception e) {
            return List.of();
        }
    }

    private int labelStartForStep(int step) {
        if (step == 0) return 0;
        return LABELS_STEP_0 + (step - 1) * LABELS_PER_STEP;
    }

    private int labelsProcessedAfterStep(int step) {
        if (step == 0) return LABELS_STEP_0;
        return LABELS_STEP_0 + step * LABELS_PER_STEP;
    }

    private int totalSteps(int labelCount) {
        if (labelCount <= LABELS_STEP_0) return 1;
        return 1 + (int) Math.ceil((double) (labelCount - LABELS_STEP_0) / LABELS_PER_STEP);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}