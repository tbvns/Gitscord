package xyz.tbvns.events.bridge;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.forums.ForumTag;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.hooks.SubscribeEvent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.kohsuke.github.GHIssue;
import xyz.tbvns.GitscordEmoji;
import xyz.tbvns.Main;
import xyz.tbvns.Utils;
import xyz.tbvns.database.DatabaseService;
import xyz.tbvns.github.GitHubAppService;
import xyz.tbvns.github.GitHubToDiscordBridge;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CreateBridge {
    @SubscribeEvent
    public void onChannelCreate(ChannelCreateEvent event) {
        if (event.getChannelType() != ChannelType.GUILD_PUBLIC_THREAD) return;

        ThreadChannel thread = event.getChannel().asThreadChannel();

        if (thread.getParentChannel().getType() != ChannelType.FORUM) return;

        String title = thread.getName();

        thread.getHistoryFromBeginning(1).queue(history -> {
            Message message = history.isEmpty()
                    ? null
                    : history.getRetrievedHistory().get(0);

            String repo = null;
            for (ForumTag appliedTag : thread.getAppliedTags()) {
                try {
                    repo = DatabaseService.instance.getRepoForTag(appliedTag.getId());
                    if (repo != null) break;
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }

            if (Main.jda.getSelfUser().getId().equals(message.getAuthor().getId())) return;

            if (repo == null) return;

            GHIssue issue = GitHubAppService.instance.createIssue(repo, title, Utils.discordMessageToGithub(message));

            try {
                DatabaseService.instance.upsertIssueThread(repo, issue.getNumber(), thread.getId(), false);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            MessageCreateData postBody = GitHubToDiscordBridge.buildContainerMessage(
                    GitscordEmoji.issue_open.getAsText() + " Issue #" + issue.getNumber() + " opened",
                    title,
                    message.getContentRaw(),
                    "Opened by **" + message.getAuthor().getEffectiveName() + "** • [View on GitHub](" + issue.getHtmlUrl() + ")"
            );

            thread.sendMessage(postBody).queue();
            thread.getManager().setName("[OPEN] " + title + " #" + issue.getNumber()).queue();

            List<String> tags = new ArrayList<>();
            Map<String, String> map = null;
            try {
                map = DatabaseService.instance.getIssueTagMappingsForRepo(repo);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            for (ForumTag appliedTag : thread.getAppliedTags()) {
                map.forEach((k, v) -> {
                    if (appliedTag.getId().equals(v)) {
                        tags.add(k);
                    }
                });
            }

            GitHubAppService.instance.setIssueLabels(repo, issue.getNumber(), tags);
        });
    }
}
