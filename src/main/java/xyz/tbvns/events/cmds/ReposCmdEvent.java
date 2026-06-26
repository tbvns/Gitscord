package xyz.tbvns.events.cmds;

import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.SubscribeEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import org.kohsuke.github.GHRepository;
import xyz.tbvns.Utils;
import xyz.tbvns.database.DatabaseService;
import xyz.tbvns.github.GitHubAppService;
import xyz.tbvns.github.RepoLinkManager;

import java.util.List;

public class ReposCmdEvent {
    @SubscribeEvent
    public void event(SlashCommandInteractionEvent e) throws Exception {
        if (!e.getFullCommandName().equals("setup")) return;
        if (DatabaseService.instance.getGitHubLoginsForDiscord(e.getUser().getId()).isEmpty()) {
            e.reply(Utils.errorMessage("Your Discord account isn't linked to your GitHub account, run /link.")).setEphemeral(true).queue();
            return;
        }

        String repo = e.getOption("repo").getAsString();
        GuildChannelUnion channel = e.getOption("channel").getAsChannel();

        if (!channel.getType().isThread()) {
            if (DatabaseService.instance.getGitHubLoginsForDiscord(e.getUser().getId()).isEmpty()) {
                e.reply(Utils.errorMessage("The selected channel isn't a forum channel.")).setEphemeral(true).queue();
                return;
            }
        }

        new RepoLinkManager().linkChannelToRepo(e, channel.getId(), repo);
    }

    @SubscribeEvent
    public void onAutoComplete(CommandAutoCompleteInteractionEvent e) throws Exception {
        if (!e.getName().equals("setup")) return;

        if (DatabaseService.instance.getGitHubLoginsForDiscord(e.getUser().getId()).isEmpty()) {
            e.replyChoice("Your Discord account isn't linked to your GitHub account, run `/link`.", "Your Discord account isn't linked to your GitHub account, run `/link`.").queue();
            return;
        }

        String typed = e.getFocusedOption().getValue().toLowerCase();
        List<GHRepository> repos = GitHubAppService.instance.getReposForDiscordUser(e.getUser().getId())
                .values().stream().flatMap(List::stream).toList();

        List<Command.Choice> choices = repos.stream()
                .filter(r -> r.getFullName().toLowerCase().contains(typed))
                .limit(25)
                .map(r -> new Command.Choice(r.getFullName(), r.getFullName()))
                .toList();

        e.replyChoices(choices).queue();
    }
}
