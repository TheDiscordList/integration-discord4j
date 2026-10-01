package com.thediscordlist.discord4j;

import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.discordjson.Id;
import discord4j.discordjson.json.ApplicationCommandData;
import discord4j.discordjson.json.ApplicationCommandOptionChoiceData;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.possible.Possible;
import discord4j.gateway.GatewayClient;
import discord4j.gateway.GatewayClientGroup;
import discord4j.gateway.GatewayConnection.State;
import discord4j.gateway.ShardInfo;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TheDiscordListTest {

    @Test
    void userAgentNamesTheLibraryAndRepository() {
        assertEquals("TheDiscordList-discord4j/" + Autoposter.VERSION
                + " (+https://github.com/TheDiscordList/integration-discord4j)", TheDiscordList.USER_AGENT);
    }

    @Test
    void topologyDetection() {
        assertEquals(Topology.allLocal(1), TheDiscordList.detect(null, 1, 1));
        assertEquals(Topology.allLocal(4), TheDiscordList.detect(null, 4, 4));
        assertEquals(new Topology(2, 4, false, 8, false), TheDiscordList.detect(new AutopostOptions.Cluster(2, 4), 2, 8));
        assertEquals(new Topology(0, 1, true, 4, true), TheDiscordList.detect(new AutopostOptions.Cluster(0, 1), 4, 4));
        // Still booting, or a subset of shards: Discord4J cannot tell which, so nothing is guessed.
        assertNull(TheDiscordList.detect(null, 3, 4));
    }

    @Test
    void statusMapping() {
        assertEquals("ready", TheDiscordList.status(State.CONNECTED));
        assertEquals("connecting", TheDiscordList.status(State.START_IDENTIFYING));
        assertEquals("connecting", TheDiscordList.status(State.RECONNECTING));
        assertEquals("resuming", TheDiscordList.status(State.START_RESUMING));
        assertEquals("resuming", TheDiscordList.status(State.RESUMING));
        assertEquals("disconnected", TheDiscordList.status(State.DISCONNECTING));
        assertEquals("disconnected", TheDiscordList.status(State.DISCONNECTED));
        assertEquals("disconnected", TheDiscordList.status(null));
    }

    @Test
    void shardReportsAndServerCount() {
        AtomicReference<State> second = new AtomicReference<>(State.RESUMING);
        GatewayClient shard0 = gateway(() -> State.CONNECTED, 42);
        GatewayClient shard1 = gateway(second::get, 0);
        TheDiscordList.D4jBot bot = new TheDiscordList.D4jBot(group(2, shard0, shard1), null);
        bot.guilds.addAll(List.of(0L << 22, 2L << 22, 1L << 22));

        assertEquals(List.of(
                Map.of("id", 0, "status", "ready", "latency_ms", 42L, "guilds", 2),
                Map.of("id", 1, "status", "resuming", "guilds", 1)), bot.shards());
        assertNull(bot.serverCount(), "a shard mid-resume means a partial cache");

        second.set(State.CONNECTED);
        assertEquals(3, bot.serverCount());
    }

    @Test
    void aShardThatLeftTheGroupIsStillReported() {
        TheDiscordList.D4jBot bot = new TheDiscordList.D4jBot(group(2, gateway(() -> State.CONNECTED, 5), null), null);
        bot.seen.add(1);

        assertEquals(Set.of(0, 1), bot.localShards());
        assertEquals(Map.of("id", 1, "status", "disconnected", "guilds", 0), bot.shards().get(1));
        assertNull(bot.serverCount());
    }

    @Test
    void readyReplacesThatShardsGuilds() {
        TheDiscordList.D4jBot bot = new TheDiscordList.D4jBot(group(2), null);
        long stale = 4L << 22;
        long otherShard = 1L << 22;
        bot.guilds.addAll(List.of(stale, otherShard));

        bot.ready(new ReadyEvent(null, ShardInfo.create(0, 2), null, null,
                Set.of(new ReadyEvent.Guild(2L << 22, true), new ReadyEvent.Guild(6L << 22, false))));

        assertEquals(Set.of(2L << 22, 6L << 22, otherShard), bot.guilds);
        assertEquals(Set.of(0), bot.localShards());
    }

    @Test
    void commandsMapToTheApiShape() {
        ApplicationCommandData slash = ApplicationCommandData.builder()
                .id(Id.of(1)).version(Id.of(1)).applicationId(Id.of(2))
                .type(Possible.of(1)).name("config").description("Configure the bot")
                .nsfw(Possible.of(true)).defaultMemberPermissions("32")
                .options(Possible.of(List.of(
                        ApplicationCommandOptionData.builder().type(2).name("logs").description("Logging")
                                .options(Possible.of(List.of(ApplicationCommandOptionData.builder()
                                        .type(1).name("set").description("Set the channel")
                                        .options(Possible.of(List.of(ApplicationCommandOptionData.builder()
                                                .type(7).name("channel").description("Where")
                                                .required(Possible.of(true)).build())))
                                        .build())))
                                .build(),
                        ApplicationCommandOptionData.builder().type(4).name("sides").description("Sides")
                                .choices(Possible.of(List.of(
                                        ApplicationCommandOptionChoiceData.builder().name("d6").value(6).build(),
                                        ApplicationCommandOptionChoiceData.builder().name("half").value(0.5).build(),
                                        ApplicationCommandOptionChoiceData.builder().name("x").value("y").build())))
                                .build())))
                .build();
        ApplicationCommandData user = ApplicationCommandData.builder()
                .id(Id.of(3)).version(Id.of(1)).applicationId(Id.of(2))
                .type(Possible.of(2)).name("Inspect").description("")
                .build();

        assertEquals(Map.of(
                "type", 1, "name", "config", "description", "Configure the bot", "nsfw", true,
                "default_member_permissions", "32",
                "options", List.of(
                        Map.of("type", 2, "name", "logs", "description", "Logging", "options", List.of(
                                Map.of("type", 1, "name", "set", "description", "Set the channel", "options", List.of(
                                        Map.of("type", 7, "name", "channel", "description", "Where", "required", true))))),
                        Map.of("type", 4, "name", "sides", "description", "Sides", "choices", List.of(
                                Map.of("name", "d6", "value", 6),
                                Map.of("name", "half", "value", 0.5),
                                Map.of("name", "x", "value", "y"))))),
                TheDiscordList.command(slash));
        assertEquals(Map.of("type", 2, "name", "Inspect"), TheDiscordList.command(user));
    }

    private static GatewayClient gateway(java.util.function.Supplier<State> state, long latencyMs) {
        return stub(GatewayClient.class, Map.of(
                "stateEvents", (Answer) args -> Flux.just(state.get()),
                "getResponseTime", (Answer) args -> Duration.ofMillis(latencyMs)));
    }

    private static GatewayClientGroup group(int shardCount, GatewayClient... clients) {
        return stub(GatewayClientGroup.class, Map.of(
                "getShardCount", (Answer) args -> shardCount,
                "find", (Answer) args -> {
                    int index = (Integer) args[0];
                    return Optional.ofNullable(index < clients.length ? clients[index] : null);
                }));
    }

    interface Answer {
        Object apply(Object[] args);
    }

    /** A proxy answering by method name; anything unlisted returns a zero value. */
    @SuppressWarnings("unchecked")
    static <T> T stub(Class<T> type, Map<String, Answer> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return type.getSimpleName();
                default:
                    break;
            }
            Answer answer = answers.get(method.getName());
            if (answer != null) {
                return answer.apply(args);
            }
            Class<?> returns = method.getReturnType();
            if (returns == boolean.class) {
                return false;
            }
            if (returns == int.class) {
                return 0;
            }
            if (returns == long.class) {
                return 0L;
            }
            return null;
        });
    }
}
