package xyz.tbvns.events.cmds;

import jakarta.mail.MessagingException;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.SubscribeEvent;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import xyz.tbvns.Utils;
import xyz.tbvns.mail.MailVerification;

public class LinkCmdEvent {
    @SubscribeEvent
    public void onCmdEvent(SlashCommandInteractionEvent e) throws MessagingException {
        if (!e.getFullCommandName().equals("link")) return;

        e.deferReply(true).queue();

        if (e.getOption("email") == null) {
            e.getHook().sendMessage(Utils.errorMessage("Please provide an email address.")).setEphemeral(true).queue();
            return;
        }

        String mail = e.getOption("email").getAsString();

        try {
            MailVerification.sendMail(mail);
        } catch (Exception er) {
            e.getHook().sendMessage(Utils.errorMessage("Failed to send mail: " + er.getMessage())).setEphemeral(true).queue();
            return;
        }

        e.getHook().sendMessage(new MessageCreateBuilder()
                        .useComponentsV2(true)
                        .addComponents(
                                Container.of(
                                        TextDisplay.of("## \uD83D\uDCE8 Email verification"),
                                        TextDisplay.of("To ensure you own this email address, a verification code has been sent to " + mail + "."),
                                        ActionRow.of(
                                                Button.primary("verify+" + mail, "Enter code"),
                                                Button.secondary("resend+" + mail, "Resend email")
                                        )
                                )
                        )
                .build()).setEphemeral(true).queue();
    }
}
