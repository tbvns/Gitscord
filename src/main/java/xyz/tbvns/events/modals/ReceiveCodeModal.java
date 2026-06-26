package xyz.tbvns.events.modals;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.SubscribeEvent;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import xyz.tbvns.Utils;
import xyz.tbvns.database.DatabaseService;
import xyz.tbvns.mail.MailVerification;

import java.awt.*;
import java.util.Optional;

public class ReceiveCodeModal {
    @SubscribeEvent
    public void event(ModalInteractionEvent e) {
        if (!e.getModalId().startsWith("code+")) return;
        try {
            String mail = e.getModalId().replaceFirst("code\\+", "");
            String code = e.getValue("code").getAsString();

            if (!MailVerification.verify(mail, code)) {
                e.reply(Utils.errorMessage("The code you provided isn't correct. Please verify you typed it right.")).setEphemeral(true).queue();
                return;
            }

            String login = DatabaseService.instance.getLoginByEmail(mail);

            if (login == null) {
                e.reply(
                        new MessageCreateBuilder()
                                .useComponentsV2(true)
                                .addComponents(Container.of(
                                        TextDisplay.of("## OOps, so close !"),
                                        TextDisplay.of("It looks like you don't have the Gitscord app installed on your github account."),
                                        TextDisplay.of("Please retry the account linking after installing the app."),
                                        ActionRow.of(
                                                Button.link("https://github.com/apps/gitscord-by-tbvns", "Install the github app")
                                        )
                                ).withAccentColor(Color.ORANGE))
                                .build()
                ).setEphemeral(true).queue();
                return;
            }

            e.reply(Utils.successMessage("Your account was successfully linked !", "link-green.png")).setEphemeral(true).queue();

            long githubUserId = DatabaseService.instance.getGitHubUserIdForLogin(login);
            DatabaseService.instance.linkDiscordToGitHub(e.getUser().getId(), login, githubUserId);

        } catch (Exception er) {
            e.reply(Utils.errorMessage("Something went wrong while trying to verify your Email.")).setEphemeral(true).queue();
            er.printStackTrace();
        }
    }
}
