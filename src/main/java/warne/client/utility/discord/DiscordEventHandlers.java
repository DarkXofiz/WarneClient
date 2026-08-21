package warne.client.utility.discord;

import java.util.Arrays;
import java.util.List;
import warne.client.utility.discord.callbacks.JoinGameCallback;
import warne.client.utility.discord.callbacks.ErroredCallback;
import warne.client.utility.discord.callbacks.ReadyCallback;
import warne.client.utility.discord.callbacks.SpectateGameCallback;
import warne.client.utility.discord.callbacks.JoinRequestCallback;
import warne.client.utility.discord.callbacks.DisconnectedCallback;
import com.sun.jna.Structure;

public class DiscordEventHandlers extends Structure {
    public DisconnectedCallback disconnected;
    public JoinRequestCallback joinRequest;
    public SpectateGameCallback spectateGame;
    public ReadyCallback ready;
    public ErroredCallback errored;
    public JoinGameCallback joinGame;
    
    protected List<String> getFieldOrder() {
        return Arrays.asList("ready", "disconnected", "errored", "joinGame", "spectateGame", "joinRequest");
    }
}