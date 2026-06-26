package xyz.tbvns.events.cmds;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.selections.SelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.SubscribeEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.kohsuke.github.GHRepository;
import xyz.tbvns.Utils;
import xyz.tbvns.database.DatabaseService;
import xyz.tbvns.github.GitHubAppService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ReposCmdEvent {
    @SubscribeEvent
    public void event(SlashCommandInteractionEvent e) throws Exception {
        if (!e.getFullCommandName().equals("repos")) return;
        if (DatabaseService.instance.getGitHubLoginsForDiscord(e.getUser().getId()).isEmpty()) {
            e.reply(Utils.errorMessage("Your Discord account isn't linked to your GitHub account, run `/link`.")).setEphemeral(true).queue();
            return;
        }
        List<List<GHRepository>> reposs = GitHubAppService.instance.getReposForDiscordUser(e.getUser().getId()).values().stream().toList();
        List<GHRepository> repositories = new ArrayList<>();
        reposs.forEach(repositories::addAll);

        StringSelectMenu.Builder selectRepo = StringSelectMenu.create("select_repo");
        int i = 0;
        for (GHRepository repository : repositories) {
            i++;
            if (i == 24) break;
            selectRepo.addOption(repository.getFullName(), "select_repo+" + repository.getFullName());
        }



        e.reply(new MessageCreateBuilder()
                        .useComponentsV2(true)
                        .addComponents(Container.of(
                                TextDisplay.of("### Select a repo:"),
                                ActionRow.of(
                                        selectRepo.build()
                                )
                        ))
                .build()).setEphemeral(true).queue();
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
                .filter(r -> r.getName().toLowerCase().contains(typed))
                .limit(25)
                .map(r -> new Command.Choice(r.getFullName(), r.getFullName()))
                .toList();

        e.replyChoices(choices).queue();
    }
}
