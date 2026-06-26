package xyz.tbvns.events.bridge;

import net.dv8tion.jda.api.entities.channel.forums.ForumTag;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateAppliedTagsEvent;
import net.dv8tion.jda.api.hooks.SubscribeEvent;
import xyz.tbvns.database.DatabaseService;
import xyz.tbvns.github.GitHubAppService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TagBridge {
    @SubscribeEvent
    public void event(ChannelUpdateAppliedTagsEvent event) throws SQLException {
        if (!event.getChannelType().isThread()) return;

        DatabaseService.ThreadIssueInfo issueInfo = DatabaseService.instance.getIssueFromThread(event.getChannel().getId());

        List<String> tags = GitHubAppService.instance.getIssueLabels(issueInfo.repoFullName(), issueInfo.issueNumber());
        List<String> currentTags = new ArrayList<>(tags);
        Map<String, String> tagMapping = DatabaseService.instance.getIssueTagMappingsForRepo(issueInfo.repoFullName());

        List<String> tagToRemove = new ArrayList<>();
        for (ForumTag removedTag : event.getRemovedTags()) {
            tagMapping.forEach((k, v) -> {
                if (v.equals(removedTag.getId())) {
                    tagToRemove.add(k);
                }
            });
        }

        List<String> tagToAdd = new ArrayList<>();
        for (ForumTag addedTag : event.getAddedTags()) {
            tagMapping.forEach((k, v) -> {
                if (v.equals(addedTag.getId())) {
                    tagToAdd.add(k);
                }
            });
        }

        tags.addAll(tagToAdd);
        tags.removeAll(tagToRemove);

        if (!currentTags.equals(tags)) {
            GitHubAppService.instance.setIssueLabels(issueInfo.repoFullName(), issueInfo.issueNumber(), tags);
        }
    }
}
