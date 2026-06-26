package xyz.tbvns.events.buttons;

import jakarta.mail.MessagingException;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.SubscribeEvent;
import net.dv8tion.jda.api.modals.Modal;
import xyz.tbvns.Utils;
import xyz.tbvns.mail.MailVerification;

public class VerificationCodeButton {
    @SubscribeEvent
    public void onEvent(ButtonInteractionEvent event) throws MessagingException {
        if (event.getButton().getCustomId().startsWith("verify+")) {
            String mail = event.getButton().getCustomId().replaceFirst("verify\\+", "");

            Modal modal = Modal.create("code+" + mail, "Verify Email Address")
                    .addComponents(Label.of("Verification Code", TextInput
                            .create("code", TextInputStyle.SHORT)
                            .setMaxLength(6)
                            .setMinLength(6)
                            .setPlaceholder("123456")
                            .setRequired(true)
                            .build())
                    ).build();


            event.replyModal(modal).queue();
        } else if (event.getButton().getCustomId().startsWith("resend+")) {
            String mail = event.getButton().getCustomId().replaceFirst("resend\\+", "");
            MailVerification.sendMail(mail);
            event.reply(Utils.successMessage("Verification email resent to " + mail + ".", "mail-green.png")).setEphemeral(true).queue();
        }
    }
}
