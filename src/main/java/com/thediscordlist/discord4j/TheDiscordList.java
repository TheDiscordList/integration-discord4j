package com.thediscordlist.discord4j;

import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.guild.GuildCreateEvent;
import discord4j.core.event.domain.guild.GuildDeleteEvent;
import discord4j.core.event.domain.lifecycle.GatewayLifecycleEvent;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.retriever.EntityRetrievalStrategy;
import discord4j.discordjson.json.ApplicationCommandData;
import discord4j.discordjson.json.ApplicationCommandOptionChoiceData;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.possible.Possible;
import discord4j.gateway.GatewayClient;
import discord4j.gateway.GatewayClientGroup;
import discord4j.gateway.GatewayConnection;
import discord4j.rest.RestClient;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TheDiscordList integration for Discord4J.
 *
 * <pre>{@code
 * GatewayDiscordClient client = DiscordClient.create(System.getenv("DISCORD_TOKEN")).login().block();
 * TheDiscordList.autopost(client, System.getenv("TDL_TOKEN"));
 * client.onDisconnect().block();
 * }</pre>
 */
public final class TheDiscordList {
    static final String USER_AGENT = "TheDiscordList-discord4j/" + Autoposter.VERSION
            + " (+https://github.com/TheDiscordList/integration-discord4j)";
    static final Duration UNRESOLVED_WARNING = Duration.ofMinutes(5);
    private static final Duration COMMANDS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STATE_TIMEOUT = Duration.ofSeconds(1);

    private TheDiscordList() {}

    /** Auto-posts for a logged-in client. */
    public static Autoposter autopost(GatewayDiscordClient client, String token) {
        return autopost(client, token, new AutopostOptions());
    }

    /**
     * Auto-posts for a logged-in client. Nothing starts until every shard of the client has appeared, so the
     * process knows it runs the whole bot, or until {@link AutopostOptions#cluster} names its place.
     */
    public static Autoposter autopost(GatewayDiscordClient client, String token, AutopostOptions options) {
        Objects.requireNonNull(client, "client");
        AutopostOptions resolved = options == null ? new AutopostOptions() : options;
        D4jBot bot = new D4jBot(client.getGatewayClientGroup(), client.getRestClient());
        Autoposter poster = Autoposter.create(token, resolved, USER_AGENT, bot);
        if (poster.isInert()) {
            return poster;
        }

        Starter starter = new Starter(poster, bot, resolved);
        Mono<String> botId = resolved.botId != null
                ? Mono.just(resolved.botId)
                : client.getRestClient().getApplicationId().map(Long::toUnsignedString);

        Disposable.Composite subscriptions = Disposables.composite(
                client.on(ReadyEvent.class).subscribe(bot::ready, starter::failed),
                client.on(GuildCreateEvent.class).subscribe(event -> {
                    if (bot.guilds.add(event.getGuild().getId().asLong())) {
                        poster.guildsChanged();
                    }
                }, starter::failed),
                client.on(GuildDeleteEvent.class).subscribe(event -> {
                    if (!event.isUnavailable() && bot.guilds.remove(event.getGuildId().asLong())) {
                        poster.guildsChanged();
                    }
                }, starter::failed),
                client.on(GatewayLifecycleEvent.class).subscribe(event -> {
                    bot.seen.add(event.getShardInfo().getIndex());
                    poster.shardsChanged();
                    starter.tryStart();
                }, starter::failed),
                // Covers a client attached after READY, when those events have already gone by.
                client.withRetrievalStrategy(EntityRetrievalStrategy.STORE).getGuilds()
                        .subscribe(guild -> bot.guilds.add(guild.getId().asLong()), error -> {}),
                botId.subscribe(starter::botId, error -> poster.warnOnce("bot-id", "Could not read the application id ("
                        + ApiClient.describe(error) + "). Pass AutopostOptions.botId.")),
                Mono.delay(UNRESOLVED_WARNING).subscribe(tick -> starter.warnIfUnresolved()));
        poster.onClose(subscriptions::dispose);
        return poster;
    }

    /**
     * Discord4J has no cross-process primitive and does not expose its sharding strategy, so the only layout
     * this process can prove is "every shard runs here". Anything else needs {@link AutopostOptions#cluster}.
     */
    static Topology detect(AutopostOptions.Cluster override, int localShards, int totalShards) {
        int total = Math.max(totalShards, 1);
        if (override != null) {
            return Topology.of(override, localShards, total);
        }
        return localShards >= total ? Topology.allLocal(total) : null;
    }

    static String status(GatewayConnection.State state) {
        if (state == null) {
            return "disconnected";
        }
        return switch (state) {
            case CONNECTED -> "ready";
            case START_RESUMING, RESUMING -> "resuming";
            case DISCONNECTING, DISCONNECTED -> "disconnected";
            default -> "connecting";
        };
    }

    /** Discord's own command shape, minus ids, versions and localisations the API ignores. */
    static Map<String, Object> command(ApplicationCommandData data) {
        Map<String, Object> out = new LinkedHashMap<>();
        data.type().toOptional().ifPresent(type -> out.put("type", type));
        out.put("name", data.name());
        putText(out, "description", data.description());
        data.nsfw().toOptional().ifPresent(nsfw -> out.put("nsfw", nsfw));
        data.defaultMemberPermissions().ifPresent(permissions -> out.put("default_member_permissions", permissions));
        putOptions(out, data.options());
        return out;
    }

    private static Map<String, Object> option(ApplicationCommandOptionData data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", data.type());
        out.put("name", data.name());
        putText(out, "description", data.description());
        data.required().toOptional().ifPresent(required -> out.put("required", required));
        List<ApplicationCommandOptionChoiceData> choices = data.choices().toOptional().orElse(List.of());
        if (!choices.isEmpty()) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (ApplicationCommandOptionChoiceData choice : choices) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", choice.name());
                entry.put("value", choice.value());
                entries.add(entry);
            }
            out.put("choices", entries);
        }
        putOptions(out, data.options());
        return out;
    }

    private static void putOptions(Map<String, Object> out, Possible<List<ApplicationCommandOptionData>> options) {
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (ApplicationCommandOptionData option : options.toOptional().orElse(List.of())) {
            mapped.add(option(option));
        }
        if (!mapped.isEmpty()) {
            out.put("options", mapped);
        }
    }

    private static void putText(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isEmpty()) {
            out.put(key, value);
        }
    }

    static final class D4jBot implements Bot {
        final GatewayClientGroup group;
        final RestClient rest;
        final Set<Long> guilds = ConcurrentHashMap.newKeySet();
        final Set<Integer> seen = ConcurrentHashMap.newKeySet();

        D4jBot(GatewayClientGroup group, RestClient rest) {
            this.group = group;
            this.rest = rest;
        }

        int shardCount() {
            return Math.max(group.getShardCount(), 1);
        }

        /** Every shard this process has run. One leaves the group while it reconnects, but it is still ours. */
        SortedSet<Integer> localShards() {
            int total = shardCount();
            for (int index = 0; index < total; index++) {
                if (group.find(index).isPresent()) {
                    seen.add(index);
                }
            }
            return new TreeSet<>(seen);
        }

        /** A fresh session lists every guild of its shard; anything else this shard knew is stale. */
        void ready(ReadyEvent event) {
            int index = event.getShardInfo().getIndex();
            int total = event.getShardInfo().getCount();
            guilds.removeIf(id -> shardOf(id, total) == index);
            for (ReadyEvent.Guild guild : event.getGuilds()) {
                guilds.add(guild.getId().asLong());
            }
            seen.add(index);
        }

        @Override
        public List<Map<String, Object>> shards() {
            int total = shardCount();
            int[] perShard = new int[total];
            for (long id : guilds) {
                perShard[shardOf(id, total)]++;
            }

            List<Map<String, Object>> reports = new ArrayList<>();
            for (int index : localShards()) {
                Optional<GatewayClient> client = group.find(index);
                Long latency = client.map(gateway -> gateway.getResponseTime().toMillis()).filter(ms -> ms > 0).orElse(null);
                String status = client.map(D4jBot::status).orElse("disconnected");
                reports.add(Bot.shard(index, status, latency, perShard[index]));
            }
            return reports;
        }

        /** Only once every shard is connected: a cache mid-load undercounts. */
        @Override
        public Integer serverCount() {
            for (int index = 0; index < shardCount(); index++) {
                if (!"ready".equals(group.find(index).map(D4jBot::status).orElse(null))) {
                    return null;
                }
            }
            return guilds.size();
        }

        @Override
        public List<Map<String, Object>> commands(String botId) {
            List<ApplicationCommandData> commands = rest.getApplicationService()
                    .getGlobalApplicationCommands(Long.parseUnsignedLong(botId))
                    .collectList()
                    .block(COMMANDS_TIMEOUT);
            return commands == null ? List.of() : commands.stream().map(TheDiscordList::command).toList();
        }

        /** The state sink replays its latest value, so this answers at once. */
        static String status(GatewayClient client) {
            try {
                return TheDiscordList.status(client.stateEvents().next().block(STATE_TIMEOUT));
            } catch (RuntimeException e) {
                return "connecting";
            }
        }

        static int shardOf(long guildId, int shardCount) {
            return (int) ((guildId >> 22) % shardCount);
        }
    }

    private static final class Starter {
        private final Autoposter poster;
        private final D4jBot bot;
        private final AutopostOptions options;
        private String botId;
        private boolean started;

        Starter(Autoposter poster, D4jBot bot, AutopostOptions options) {
            this.poster = poster;
            this.bot = bot;
            this.options = options;
        }

        synchronized void botId(String id) {
            botId = id;
            tryStart();
        }

        synchronized void tryStart() {
            if (started || botId == null) {
                return;
            }
            Topology topology = detect(options.cluster, bot.localShards().size(), bot.shardCount());
            if (topology == null) {
                return;
            }
            started = true;
            poster.build(botId, topology);
            poster.startAll();
        }

        synchronized void warnIfUnresolved() {
            if (!started && botId != null) {
                poster.warnOnce("topology", "This process runs " + bot.localShards().size() + " of "
                        + bot.shardCount() + " shards, so it cannot tell how many clusters the bot runs. Nothing is "
                        + "posted until you pass AutopostOptions.cluster(id, count), plus serverCount(...) for stats.");
            }
        }

        void failed(Throwable error) {
            poster.warnOnce("events", "TheDiscordList stopped receiving Discord4J events: " + ApiClient.describe(error));
        }
    }
}
