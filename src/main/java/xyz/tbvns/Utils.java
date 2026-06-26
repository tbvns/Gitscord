package xyz.tbvns;

import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.awt.*;

public class Utils {
    public static MessageCreateData errorMessage(String error) {
        FileUpload upload = FileUpload.fromData(
                Utils.class.getClassLoader().getResourceAsStream("icons/error-red.png"),
                "error-red.png"
        );

        return new MessageCreateBuilder()
                .useComponentsV2(true)
                .addComponents(Container.of(
                        Section.of(
                                Thumbnail.fromFile(upload),
                                TextDisplay.of("# An error occurred"),
                                TextDisplay.of(error)
                        )
                ).withAccentColor(Color.RED))
                .addFiles(upload)
                .build();
    }

    public static MessageCreateData successMessage(String message, String icon) {
        FileUpload upload = FileUpload.fromData(
                Utils.class.getClassLoader().getResourceAsStream("icons/" + icon),
                "error-red.png"
        );

        return new MessageCreateBuilder()
                .useComponentsV2(true)
                .addComponents(Container.of(
                        Section.of(
                                Thumbnail.fromFile(upload),
                                TextDisplay.of("# Success !"),
                                TextDisplay.of(message)
                        )
                ).withAccentColor(Color.GREEN))
                .addFiles(upload)
                .build();
    }
    
    public static String discordMessageToGithub(Message message) {
        StringBuilder attachementString = new StringBuilder();

        if (!message.getAttachments().isEmpty()) {
            attachementString.append("<hr>\n\n### Discord Attachments:");
        }

        for (Message.Attachment attachment : message.getAttachments()) {
            if (attachment.isImage()) {
                attachementString.append("\n");
                attachementString.append("![").append(attachment.getFileName()).append("](").append(attachment.getUrl()).append(")");
            } else {
                attachementString.append("\n[Attachment: ").append(attachment.getFileName()).append("](").append(attachment.getUrl()).append(")");
            }
        }

        String html = """
            <div>
                <img src="{icon_url}" width="30" height="30" align="middle" />
                <b align="middle">{username}</b>
            </div>
            {body}
            
            ###### *Bridged from Discord via Gitscord.*
            """;

        html = html
                .replace("{icon_url}", message.getAuthor().getAvatarUrl())
                .replace("{username}", message.getAuthor().getEffectiveName())
                .replace("{body}", message.getContentRaw() + attachementString)
        ;

        return html;
    }
}
