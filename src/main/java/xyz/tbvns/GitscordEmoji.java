package xyz.tbvns;

import net.dv8tion.jda.api.entities.emoji.Emoji;

public enum GitscordEmoji {
    issue_open("issueopen", 1520091093193064579l, false),
    issue_closed_completed("issueclosedcompleted", 1520091090592465097l, false),
    issue_closed_not_planned("issueclosednotplanned", 1520091091851022398l, false),
    comment("comment", 1520109097293320254l, false),
    person("person", 1520112123257163956l, false),
    tag("tag", 1520113906281091163l, false),
    ;

    public final String name;
    public final long id;
    public final boolean animated;

    public Emoji getAsEmojji() {
        return Emoji.fromCustom(name, id, false);
    }

    public String getAsText() {
        return Emoji.fromCustom(name, id, false).getFormatted();
    }

    GitscordEmoji(String name, long id, boolean animated) {
        this.name = name;
        this.id = id;
        this.animated = animated;
    }
}
