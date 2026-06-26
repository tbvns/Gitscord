package xyz.tbvns;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.hooks.AnnotatedEventManager;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import xyz.tbvns.events.bridge.CommentsBridge;
import xyz.tbvns.events.bridge.CreateBridge;
import xyz.tbvns.events.bridge.TagBridge;
import xyz.tbvns.events.buttons.VerificationCodeButton;
import xyz.tbvns.events.cmds.LinkCmdEvent;
import xyz.tbvns.events.cmds.ReposCmdEvent;
import xyz.tbvns.events.modals.ReceiveCodeModal;
import xyz.tbvns.github.RepoLinkManager;

@SpringBootApplication
public class Main {

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    public static JDA jda;

    @Bean
    public JDA jda() throws InterruptedException {
        jda = JDABuilder.createDefault(System.getenv("discord_token"))
                .enableIntents(GatewayIntent.GUILD_MESSAGES)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .setActivity(Activity.customStatus("Watching GitHub for issues"))
                .addEventListeners(new LinkCmdEvent())
                .addEventListeners(new VerificationCodeButton())
                .addEventListeners(new ReceiveCodeModal())
                .addEventListeners(new ReposCmdEvent())
                .addEventListeners(new RepoLinkManager())

                .addEventListeners(new CommentsBridge())
                .addEventListeners(new TagBridge())
                .addEventListeners(new CreateBridge())
                .setEventManager(new AnnotatedEventManager())
                .build()
                .awaitReady();

        jda.upsertCommand("setup", "Setup a bridge for a repository.")
                .addOption(OptionType.STRING, "repo", "Repository name", true, true)
                .addOption(OptionType.CHANNEL, "channel", "Bridge channel", true)
                .setDefaultPermissions(net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
                        .enabledFor(Permission.ADMINISTRATOR)).queue();
        jda.upsertCommand("help", "Show a guide explaining how to use this bot.")
                .setDefaultPermissions(net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
                        .enabledFor(Permission.ADMINISTRATOR)).queue();
        jda.upsertCommand("link", "Link your Discord account with your GitHub account.")
                .addOption(OptionType.STRING, "email", "The Email address linked to your github account.", true)
                .queue();
        return jda;
    }
}
