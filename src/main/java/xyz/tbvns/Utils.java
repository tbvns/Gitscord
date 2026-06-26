package xyz.tbvns;

import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.awt.*;
import java.io.InputStream;

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
}
