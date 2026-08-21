package warne.client.utility.discord.callbacks;

import warne.client.utility.discord.DiscordUser;
import com.sun.jna.Callback;

public interface ReadyCallback extends Callback {
    void apply(final DiscordUser p0);
}
