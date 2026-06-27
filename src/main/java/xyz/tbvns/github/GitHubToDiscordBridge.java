package xyz.tbvns.github;

import com.fasterxml.jackson.databind.JsonNode;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.forums.ForumTag;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import xyz.tbvns.GitscordEmoji;
import xyz.tbvns.database.DatabaseService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitHubToDiscordBridge {
    private static final Logger log = LoggerFactory.getLogger(GitHubToDiscordBridge.class);
    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
            "(?:\\b(?<closeword>close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s+)?" +
            "(?:(?<refrepo>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+))?#(?<num>\\d+)",
            Pattern.CASE_INSENSITIVE);

    @Autowired
    private JDA jda;

    private DatabaseService db = DatabaseService.instance;

    public void handleIssueEvent(JsonNode root) {
        String action = root.path("action").asText();
        JsonNode issue = root.path("issue");
        JsonNode changes = root.path("changes");
        JsonNode repository = root.path("repository");
        String repoFullName = repository.path("full_name").asText();

        switch (action) {
            case "opened" -> onIssueOpened(repoFullName, issue);
            case "closed" -> onIssueStatusChanged(repoFullName, issue, true);
            case "reopened" -> onIssueStatusChanged(repoFullName, issue, false);
            case "deleted" -> onIssueDeleted(repoFullName, issue);
            case "labeled", "unlabeled" -> {
                String senderLogin = root.path("sender").path("login").asText("");
                if (!senderLogin.equals("gitscord-by-tbvns[bot]")) {
                    onIssueLabelsChanged(repoFullName, issue);
                }
            }
            case "assigned", "unassigned" -> onIssueAssignmentChanged(repoFullName, issue, action, root.path("assignee"));
            case "edited" -> onIssueEdited(repoFullName, issue, changes);
            default -> log.debug("Unhandled issues action: {}", action);
        }

        // Body edits/opens can also contain cross-references to other issues/PRs.
        if ("opened".equals(action) || "edited".equals(action)) {
            scanForReferences(repoFullName, issue.path("number").asInt(-1), issue.path("title").asText(""),
                    issue.path("body").asText(""), issue.path("html_url").asText(""));
        }
    }

    private void onIssueOpened(String repoFullName, JsonNode issue) {
        try {
            String forumChannelId = db.getForumChannelForRepo(repoFullName);
            if (forumChannelId == null) return; // repo not linked

            ForumChannel forum = jda.getForumChannelById(forumChannelId);
            if (forum == null) {
                log.warn("Forum channel {} for repo {} not found", forumChannelId, repoFullName);
                return;
            }

            int number = issue.path("number").asInt();
            String title = issue.path("title").asText("Untitled issue");
            String body = issue.path("body").asText("");
            String url = issue.path("html_url").asText("");
            String author = issue.path("user").path("login").asText("unknown");
            String authorUrl = issue.path("user").path("avatar_url").asText("unknown");

            if (db.getThreadIdForIssue(repoFullName, number) != null) return;

            List<ForumTag> tags = resolveTags(forum, repoFullName, issue);

            MessageCreateData postBody = buildContainerMessage(
                    GitscordEmoji.issue_open.getAsText() + " Issue #" + number + " opened",
                    title,
                    body,
                    "Opened by **" + author + "** • [View on GitHub](" + url + ")"
            );

            String threadName = truncate("[OPEN] " + title + " #" + number, 100);

            var action = forum.createForumPost(threadName, postBody);
            if (!tags.isEmpty()) action = action.setTags(tags);

            action.queue(post -> {
                ThreadChannel thread = post.getThreadChannel();
                try {
                    db.upsertIssueThread(repoFullName, number, thread.getId(), false);
                } catch (SQLException e) {
                    log.error("Failed to persist thread mapping for {}#{}", repoFullName, number, e);
                }
            }, err -> log.error("Failed to create forum post for {}#{}", repoFullName, number, err));

        } catch (Exception e) {
            log.error("Failed handling issue opened for {}", repoFullName, e);
        }
    }

    private void onIssueStatusChanged(String repoFullName, JsonNode issue, boolean closed) {
        int number = issue.path("number").asInt();
        withThread(repoFullName, number, thread -> {
            String reason = issue.path("state_reason").asText(null);
            String emoji = closed ? ( reason.equals("completed") ?
                                    GitscordEmoji.issue_closed_completed.getAsText() :
                                    GitscordEmoji.issue_closed_not_planned.getAsText()
                    ) : GitscordEmoji.issue_open.getAsText();
            String word = closed ? "closed" : "reopened";

            String statusText = "";
            if (!closed) {
                statusText = "[OPEN]";
            } else if (reason.equals("completed")) {
                statusText = "[CLOSED]";
            } else {
                statusText = "[" + reason.toUpperCase(Locale.ROOT).replace("_", "") + "]";
            }

            String newName = truncate(statusText + " ⸱ " + issue.path("title").asText() + " #" + number, 100);

            thread.getManager().setName(newName).queue();

            String note = emoji + " **Issue " + word + "**" +
                    (reason != null && !reason.isBlank() ? " (" + reason + ")" : "");

            thread.sendMessage(new MessageCreateBuilder()
                    .useComponentsV2(true)
                    .addComponents(Container.of(TextDisplay.of(note)))
                    .build()
            ).queue(null, err -> log.error("Failed to post status note", err));

            if (closed) {
                thread.getManager().queue(null,
                        err -> log.warn("Could not archive thread {}", thread.getId(), err));
            } else {
                thread.getManager().queue(null,
                        err -> log.warn("Could not unarchive thread {}", thread.getId(), err));
            }
        });
    }

    private void onIssueDeleted(String repoFullName, JsonNode issue) {
        int number = issue.path("number").asInt();
        try {
            String threadId = db.getThreadIdForIssue(repoFullName, number);
            if (threadId == null) return;

            ThreadChannel thread = jda.getThreadChannelById(threadId);
            db.deleteIssueThread(repoFullName, number);

            if (thread != null) {
                thread.delete().queue(null, err -> log.warn("Failed to delete thread {}", threadId, err));
            }
        } catch (SQLException e) {
            log.error("DB error deleting thread mapping for {}#{}", repoFullName, number, e);
        }
    }

    private void onIssueLabelsChanged(String repoFullName, JsonNode issue) {
        int number = issue.path("number").asInt();

        withThreadAndForum(repoFullName, number, (thread, forum) -> {
            List<ForumTag> tags = resolveTags(forum, repoFullName, issue);
            thread.getManager().setAppliedTags(tags).queue(
                    null, err -> log.error("Failed to update tags on thread {}", thread.getId(), err));

            StringBuilder labelList = new StringBuilder();
            for (JsonNode label : issue.path("labels")) {
                if (labelList.length() > 0) labelList.append(", ");
                labelList.append('`').append(label.path("name").asText()).append('`');
            }

            thread.sendMessage(new MessageCreateBuilder()
                    .useComponentsV2(true)
                    .addComponents(Container.of(
                            TextDisplay.of(GitscordEmoji.tag.getAsText() + " **Labels updated**"),
                            TextDisplay.of(labelList.length() > 0 ? "Current labels: " + labelList : "No labels remaining.")
                    ))
                    .build()
            ).queue(null, err -> log.error("Failed to post label update note", err));
        });
    }

    private void onIssueAssignmentChanged(String repoFullName, JsonNode issue, String action, JsonNode assigneeNode) {
        int number = issue.path("number").asInt();
        withThread(repoFullName, number, thread -> {
            String assignee = assigneeNode.path("login").asText("someone");
            String verb = "assigned".equals(action) ? "assigned to" : "unassigned from";
            String note = GitscordEmoji.person.getAsText() + " **" + assignee + "** was " + verb + " this issue";

            thread.sendMessage(new MessageCreateBuilder()
                    .useComponentsV2(true)
                    .addComponents(Container.of(TextDisplay.of(note)))
                    .build()
            ).queue(null, err -> log.error("Failed to post assignment note", err));
        });
    }

    private void onIssueEdited(String repoFullName, JsonNode issue, JsonNode changes) {
        int number = issue.path("number").asInt();
        JsonNode titleChange = changes.path("title");

        if (titleChange.isMissingNode()) return;

        String newTitle = issue.path("title").asText("");
        withThread(repoFullName, number, thread -> {
            String status = issue.path("state").asText(null);
            String reason = issue.path("state_reason").asText(null);

            String statusText = "";
            if (status.equals("open")) {
                statusText = "[OPEN]";
            } else if (reason.equals("completed")) {
                statusText = "[CLOSED]";
            } else {
                statusText = "[" + reason.toUpperCase(Locale.ROOT).replace("_", "") + "]";
            }

            String newName = truncate(statusText + " ⸱ " + newTitle + " #" + number, 100);
            thread.getManager().setName(newName).queue(
                    null, err -> log.warn("Failed to rename thread {}", thread.getId(), err));
        });
    }

    public void handleIssueCommentEvent(JsonNode root) {
        String action = root.path("action").asText();
        if (!"created".equals(action)) return;

        JsonNode issue = root.path("issue");
        JsonNode comment = root.path("comment");
        String repoFullName = root.path("repository").path("full_name").asText();
        int number = issue.path("number").asInt();

        String author = comment.path("user").path("login").asText("unknown");
        String body = comment.path("body").asText("");
        String url = comment.path("html_url").asText("");

        if (author.equals("gitscord-by-tbvns[bot]")) return;

        withThread(repoFullName, number, thread -> {
            MessageCreateData msg = buildContainerMessage(
                    GitscordEmoji.comment.getAsText() + " New comment from " + author,
                    null,
                    body,
                    "[View on GitHub](" + url + ")"
            );
            thread.sendMessage(msg).queue(null, err -> log.error("Failed to mirror comment", err));
        });

        scanForReferences(repoFullName, number, null, body, url);
    }

    public void handlePullRequestEvent(JsonNode root) {
        String action = root.path("action").asText();
        if (!"opened".equals(action) && !"edited".equals(action)) return;

        JsonNode pr = root.path("pull_request");
        String repoFullName = root.path("repository").path("full_name").asText();
        String body = pr.path("body").asText("");
        String title = pr.path("title").asText("");
        String url = pr.path("html_url").asText("");
        int prNumber = pr.path("number").asInt(-1);

        scanForReferences(repoFullName, prNumber, title, body, url);
    }

    /** Finds the live ThreadChannel for a repo+issue number, if tracked, and runs the consumer on it. */
    private void withThread(String repoFullName, int issueNumber, java.util.function.Consumer<ThreadChannel> consumer) {
        try {
            String threadId = db.getThreadIdForIssue(repoFullName, issueNumber);
            if (threadId == null) return;
            ThreadChannel thread = jda.getThreadChannelById(threadId);
            if (thread == null) {
                log.warn("Tracked thread {} for {}#{} no longer exists", threadId, repoFullName, issueNumber);
                return;
            }
            consumer.accept(thread);
        } catch (SQLException e) {
            log.error("DB lookup failed for {}#{}", repoFullName, issueNumber, e);
        }
    }

    private interface ThreadAndForumConsumer {
        void accept(ThreadChannel thread, ForumChannel forum);
    }

    private void withThreadAndForum(String repoFullName, int issueNumber, ThreadAndForumConsumer consumer) {
        withThread(repoFullName, issueNumber, thread -> {
            try {
                String forumChannelId = db.getForumChannelForRepo(repoFullName);
                ForumChannel forum = forumChannelId != null ? jda.getForumChannelById(forumChannelId) : null;
                if (forum == null) return;
                consumer.accept(thread, forum);
            } catch (SQLException e) {
                log.error("DB lookup failed for forum channel of {}", repoFullName, e);
            }
        });
    }

    /** Resolves the set of forum tags that should be applied to an issue's thread, from its current labels. */
    private List<ForumTag> resolveTags(ForumChannel forum, String repoFullName, JsonNode issue) {
        List<ForumTag> tags = new ArrayList<>();
        try {
            String repoTagId = db.getRepoTagId(repoFullName);
            if (repoTagId != null && !repoTagId.isBlank()) {
                ForumTag repoTag = forum.getAvailableTagById(repoTagId);
                if (repoTag != null) tags.add(repoTag);
            }

            for (JsonNode label : issue.path("labels")) {
                String labelName = label.path("name").asText();
                String discordTagId = db.getDiscordTagForIssueLabel(repoFullName, labelName);
                if (discordTagId == null) continue;
                ForumTag tag = forum.getAvailableTagById(discordTagId);
                if (tag != null && !tags.contains(tag)) tags.add(tag);
            }
        } catch (SQLException e) {
            log.error("Failed to resolve tags for {}", repoFullName, e);
        }

        // Discord forum posts allow at most 5 applied tags; trim defensively rather than
        // letting the REST call fail outright if more labels map to tags than that.
        final int maxTagsPerPost = 5;
        if (tags.size() > maxTagsPerPost) {
            tags = tags.subList(0, maxTagsPerPost);
        }
        return tags;
    }

    /** Scans free text (issue/PR body or comment) for "#123" / "fixes #123" style references and notifies the target thread. */
    private void scanForReferences(String sourceRepo, int sourceNumber, String sourceTitle, String text, String sourceUrl) {
        if (text == null || text.isBlank()) return;

        Matcher matcher = REFERENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            int refNumber;
            try {
                refNumber = Integer.parseInt(matcher.group("num"));
            } catch (NumberFormatException e) {
                continue;
            }
            if (refNumber == sourceNumber) continue; // a PR/issue referencing itself isn't interesting

            String refRepo = matcher.group("refrepo");
            String targetRepo = (refRepo != null && !refRepo.isBlank()) ? refRepo : sourceRepo;

            String closeWord = matcher.group("closeword");
            String relation = closeWord != null ? "will be closed by" : "referenced by";

            String sourceDescriptor = sourceNumber >= 0
                    ? "#" + sourceNumber + (sourceTitle != null && !sourceTitle.isBlank() ? " (" + sourceTitle + ")" : "")
                    : "a recent update";

            withThread(targetRepo, refNumber, thread -> {
                String note = "🔗 This issue " + relation + " **" + sourceRepo + "#" + sourceNumber + "**" +
                        (sourceTitle != null && !sourceTitle.isBlank() ? " — \"" + truncate(sourceTitle, 80) + "\"" : "") +
                        (sourceUrl != null && !sourceUrl.isBlank() ? "\n[View source](" + sourceUrl + ")" : "");

                thread.sendMessage(new MessageCreateBuilder()
                        .useComponentsV2(true)
                        .addComponents(Container.of(TextDisplay.of(note)))
                        .build()
                ).queue(null, err -> log.error("Failed to post cross-reference note", err));
            });
        }
    }

    /** Builds a standard Components V2 container message: heading, optional subtitle, body, footer. */
    public static MessageCreateData buildContainerMessage(String heading, String subtitle, String body, String footer) {
        List<net.dv8tion.jda.api.components.container.ContainerChildComponent> children = new ArrayList<>();

        children.add(TextDisplay.of("### " + heading));
        if (subtitle != null && !subtitle.isBlank()) {
            children.add(TextDisplay.of("**" + truncate(subtitle, 256) + "**"));
        }
        if (body != null && !body.isBlank()) {
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
            children.add(TextDisplay.of(truncate(body, 3500)));
        }
        if (footer != null && !footer.isBlank()) {
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
            children.add(TextDisplay.of("-# " + footer));
        }

        return new MessageCreateBuilder()
                .useComponentsV2(true)
                .addComponents(Container.of(children))
                .build();
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}