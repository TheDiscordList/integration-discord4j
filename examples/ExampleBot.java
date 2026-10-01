// A minimal bot that publishes to TheDiscordList.
//
// From the repository root:
//   mvn -q package -DskipTests
//   mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
//   DISCORD_TOKEN=... TDL_TOKEN=... java -cp "target/classes:$(cat cp.txt)" examples/ExampleBot.java

import com.thediscordlist.discord4j.AutopostOptions;
import com.thediscordlist.discord4j.Autoposter;
import com.thediscordlist.discord4j.TheDiscordList;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;

public class ExampleBot {
    public static void main(String[] args) {
        GatewayDiscordClient client = DiscordClient.create(System.getenv("DISCORD_TOKEN")).login().block();

        Autoposter poster = TheDiscordList.autopost(client, System.getenv("TDL_TOKEN"), new AutopostOptions()
                .listener(new Autoposter.Listener() {
                    @Override
                    public void onPosted(Autoposter.Kind type, int status) {
                        System.out.println("posted " + type + " (" + status + ")");
                    }

                    @Override
                    public void onError(Autoposter.Failure failure) {
                        System.err.println(failure);
                    }

                    @Override
                    public void onDisabled(Autoposter.Kind type, Integer status, String reason) {
                        System.err.println(type + " disabled: " + reason);
                    }
                }));

        client.onDisconnect().block();
        poster.close();
    }
}
