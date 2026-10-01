# com.thediscordlist:integration-discord4j

Publish your Discord4J bot's server count, shard heartbeats and slash commands
to [TheDiscordList](https://thediscordlist.com) automatically, and verify its
inbound webhooks.

No dependencies beyond Discord4J: HTTP, HMAC and JSON come from the JDK and the
Jackson that Discord4J already ships.

```xml
<dependency>
    <groupId>com.thediscordlist</groupId>
    <artifactId>integration-discord4j</artifactId>
    <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("com.thediscordlist:integration-discord4j:0.1.0")
```

## Quickstart

```java
import com.thediscordlist.discord4j.TheDiscordList;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;

GatewayDiscordClient client = DiscordClient.create(System.getenv("DISCORD_TOKEN")).login().block();

TheDiscordList.autopost(client, System.getenv("TDL_TOKEN"));

client.onDisconnect().block();
```

That is the whole integration, and it is also the recommended production setup.
Inside `DiscordClient#withGateway(client -> ...)` the call is the same. It
starts three publishers:

| Publisher | What it sends | Cadence |
| --- | --- | --- |
| Stats | `server_count`, `shard_count` | 5s after start, then every 5 min, debounced on guild join/leave |
| Heartbeat | per-shard status, latency and guild counts | every ~30s, plus shortly after any gateway lifecycle event |
| Commands | your registered global slash commands | once, ~10s after start |

Your **stats token** is on the listing's manage page, under the API tab. It is
scoped to one listing, so it is the only credential this package needs.

With a missing token, `autopost` logs one warning and returns an inert
`Autoposter`: a forgotten environment variable never takes down a deploy.

## Sharding

| Layout | `server_count` comes from | Stats posted by | Heartbeats |
| --- | --- | --- | --- |
| One shard | READY and guild events, seeded from the store | this process | cluster 0 of 1 |
| Every shard in one process (the default `ShardingStrategy.recommended()`) | the same, across all shards | this process | cluster 0 of 1, every shard |
| Shards split across processes (`ShardingStrategy.builder().indices(...)`) | your `serverCount(...)` | the process with `cluster(0, n)` | only with `cluster(id, count)` |

Every process heartbeats for **itself**, with its own shards. A manager
reporting "all fine" for a process whose host has died is exactly the failure
the status page exists to catch.

With several shards, `login()` returns as soon as the first one connects, and
Discord4J does not expose which shards a process was told to run. So the
package starts once every shard index has appeared in the client's
`GatewayClientGroup`, which proves this process runs the whole bot. That takes
as long as Discord4J's identify queue, usually seconds. A process that never
sees every shard is running a subset: it logs a warning after five minutes and
posts nothing until you name its place.

```java
TheDiscordList.autopost(client, System.getenv("TDL_TOKEN"), new AutopostOptions()
        .cluster(Integer.parseInt(System.getenv("CLUSTER_ID")), Integer.parseInt(System.getenv("CLUSTER_COUNT")))
        .serverCount(() -> myBus.sumGuildCounts()));
```

> **If the cluster count cannot be determined, heartbeats do not start.** The
> API hard-deletes every cluster row whose id is `>= cluster_count` in the
> request, so one process guessing `1` would erase all of its siblings and
> their monitoring history. Losing a status page beats losing data.

Guilds are counted from each shard's READY list plus `GuildCreateEvent` and
`GuildDeleteEvent`, so guilds that are temporarily unavailable still count, and
a fresh READY replaces whatever its shard knew before. This needs the `GUILDS`
intent, which Discord4J enables by default. The count is only read once every
shard is `CONNECTED`, so a half-loaded cache is never published.

## Options

```java
TheDiscordList.autopost(client, token, new AutopostOptions()
        .botId("161660517914509312")           // default: RestClient#getApplicationId()
        .baseUrl("https://thediscordlist.com/api")

        .stats(true)
        .statsInterval(Duration.ofMinutes(5))
        .statsDebounce(Duration.ofSeconds(20))
        .statsMinGap(Duration.ofSeconds(60))
        .heartbeat(true)
        .heartbeatInterval(Duration.ofSeconds(30))
        .commands(true)
        .postEmptyCommands(false)

        .cluster(0, 1)
        .serverCount(() -> 1234)
        .listener(myListener));
```

`statsMinGap` is a hard floor between two stats POSTs. A join storm, a mass
reconnect and a manual `postStats()` all route through it, so the ceiling is
structurally one request per minute. An unchanged count is not re-posted, except
every 30 minutes so the listing never looks abandoned.

`postEmptyCommands` is `false` because posting an empty list **clears** the
command list on your listing. Turn it on only if that is what you mean.

## Events and manual posting

```java
Autoposter poster = TheDiscordList.autopost(client, token, new AutopostOptions()
        .listener(new Autoposter.Listener() {
            @Override public void onPosted(Autoposter.Kind type, int status) {}
            @Override public void onError(Autoposter.Failure failure) {} // type, status, retryable, message
            @Override public void onDisabled(Autoposter.Kind type, Integer status, String reason) {}
        }));

Mono<Autoposter.PostResult> stats = Mono.fromFuture(poster.postStats()); // PostResult(ok, status, skipped)
poster.postCommands();
poster.postHeartbeat();

poster.close();
```

Nothing in this package throws at your bot. The `postX()` futures never
complete exceptionally, every timer and event callback is guarded, and without
a listener, errors and disables are logged through `System.Logger` (JUL by
default; add `org.slf4j:slf4j-jdk-platform-logging` to route them into SLF4J).
HTTP calls are blocking and run on the `Autoposter`'s own three daemon threads,
never on a Reactor scheduler, so listener callbacks arrive there.

An explicit `postX()` also re-arms a publisher that disabled itself, so you can
retry after fixing a token or a listing id without restarting.

`close()` stops every timer and disposes every event subscription it made.

## Webhooks

`Webhooks` uses only the JDK and Jackson, so a webhook-only service can use it
without logging a bot in.

### Spring Boot

```java
@RestController
class TdlWebhookController {
    private final String secret = System.getenv("TDL_WEBHOOK_SECRET");

    @PostMapping("/webhooks/tdl")
    ResponseEntity<Void> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = Webhooks.SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(value = Webhooks.DELIVERY_HEADER, required = false) String delivery) {
        Webhooks.Event event;
        try {
            event = Webhooks.verify(body, signature, secret);
        } catch (Webhooks.VerificationException e) {
            return ResponseEntity.badRequest().build(); // e.code() says why
        }

        switch (event.event()) {
            case "listing.upvoted" -> thank(event.data().path("user"));
            case "listing.bumped" -> announce(event.data().path("points").asInt());
            default -> { } // webhook.ping, and names this version does not know yet
        }
        return ResponseEntity.noContent().build();
    }
}
```

On WebFlux, take the body as `Mono<byte[]>` (or `DataBuffer`) the same way;
never as a parsed object.

`body` must be the exact bytes that arrived. A parsed object re-serialised to
JSON changes escaping and key order and can never verify: the signature covers
bytes, not meaning.

`Webhooks.verify` checks `X-TDL-Signature` (`t=<unix seconds>,v1=<hex>`),
rejects timestamps more than five minutes away in either direction, compares
in constant time, and returns the parsed envelope. Failures throw
`VerificationException` with a `code()`: `MISSING_SIGNATURE`,
`MALFORMED_SIGNATURE`, `STALE_TIMESTAMP`, `INVALID_SIGNATURE` or
`MALFORMED_BODY`.

### Events

| `event.event()` | `event.data()` |
| --- | --- |
| `listing.bumped` | `points`, `bumped_at`, `next_bump_at`, `user` |
| `listing.upvoted` | `voted_at`, `total_upvotes`, `user` |
| `server.joined` | `joined_at`, `user` (server listings only) |
| `bot.added` | `added_at`, `user` |
| `bot.stats_reported` | `guild_count`, `shard_count`, `reported_at` |
| `webhook.ping` | `message` |

`data` is a Jackson `JsonNode`, so a field added tomorrow is readable today, and
an event name this version does not know still parses.

Two of them are not what their names suggest. `bot.added` is a **click** event,
dispatched on the install hand-off: Discord never tells anyone whether the
install completed. And `bot.stats_reported` is not an echo of your own POST: it
also fires when the owner edits the listing in the web UI, and from the
heartbeat's internal stats forward.

### Rules for a healthy endpoint

- **Always answer 2xx**, including for `webhook.ping` and for event names you
  do not recognise. The sender disables an endpoint after 10 consecutive failed
  deliveries. A 500 on an unknown event is a self-inflicted outage.
- A disabled endpoint is probed with a `webhook.ping` every 6 hours for 7 days,
  and **a 2xx to that probe re-enables it**.
- **Register an exact `https://` URL with no redirect.** The sender treats 3xx
  as a failure.
- **Deduplicate on `X-TDL-Delivery`** (equal to `event.id()`). It is stable
  across all retries of one delivery; a manual "redeliver" from the UI mints a
  new one deliberately.
- **Rotating the secret has no grace window.** Deliveries already queued under
  the old secret become unverifiable.

## Plans, limits and known behaviour

- **Heartbeats need Gold or higher.** On a `403` the heartbeat publisher
  suspends and re-probes every 30 minutes rather than disabling: the plan is
  checked per request, so a bot can gain it mid-run.
- **Rate limits are shared.** Stats and commands share 60 requests a minute per
  client IP, heartbeats get 120. A `429` suspends stats and commands together,
  honouring `Retry-After` and `X-RateLimit-Reset`; heartbeats are never held
  back by it, since a dropped heartbeat reads as "down". Other bots behind the
  same egress IP count against you.
- **`404` never means "bad token".** It means the id is unknown or belongs to a
  server listing, and disables only that publisher. A `401` is the bad token,
  and stops every publisher.
- `409` on commands means the listing is not set up for slash commands; the
  commands publisher switches off.
- Transient failures (5xx, timeouts, network) are retried twice with jittered
  exponential backoff, then left to the next run.

## Requirements

- Java 17 or newer
- Discord4J 3.2.x or 3.3.x (`com.discord4j:discord4j-core`), which you add
  yourself; this package declares it `provided`. Tested against 3.2.10 and
  3.3.3.

## Publishing (maintainers)

Releases go to Maven Central through the Sonatype Central Portal, signed with
GPG, from `.github/workflows/release.yml` on a `v*` tag.

One-time setup:

1. Sign in at [central.sonatype.com](https://central.sonatype.com), add the
   namespace `com.thediscordlist` and verify it with the DNS TXT record the
   portal shows for `thediscordlist.com`.
2. Under **View Account → Generate User Token**, create a token and store its
   two halves as the repository (or organisation) secrets
   `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`.
3. Create a signing key (`gpg --quick-generate-key "TheDiscordList" rsa4096 sign 2y`),
   publish it (`gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`),
   and store `gpg --armor --export-secret-keys <KEY_ID>` as
   `MAVEN_GPG_PRIVATE_KEY` and its passphrase as `MAVEN_GPG_PASSPHRASE`.

Each release:

1. Set the version in `pom.xml` and `Autoposter.VERSION` (a test fails when
   they differ), and move the `Unreleased` notes in `CHANGELOG.md` under it.
2. Commit, then `git tag v0.2.0 && git push origin v0.2.0`. The workflow
   checks the tag matches the POM, runs the tests, signs, uploads and waits
   until the portal reports the release published.

## License

MIT
