package xyz.tbvns.events.bridge;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.SubscribeEvent;
import xyz.tbvns.Main;
import xyz.tbvns.Utils;
import xyz.tbvns.database.DatabaseService;
import xyz.tbvns.github.GitHubAppService;

import java.sql.SQLException;

public class CommentsBridge {
    @SubscribeEvent
    public void event(MessageReceivedEvent event) throws SQLException {
        if (!event.getChannelType().isThread()) return;
        if (Main.jda.getSelfUser().getId().equals(event.getAuthor().getId())) return;

        DatabaseService.ThreadIssueInfo issueInfo = DatabaseService.instance.getIssueFromThread(event.getChannel().getId());

        if (issueInfo == null) return;

        String html = Utils.discordMessageToGithub(event.getMessage());

        GitHubAppService.instance.addCommentToIssue(issueInfo.repoFullName(), issueInfo.issueNumber(), html);
    }
}
