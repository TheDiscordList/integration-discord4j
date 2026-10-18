# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0] - 2026-10-18

### Added

- `listing.bump_ready` in the documented webhook events, with `ready_at`.

### Changed

- A gateway lifecycle event beats no sooner than 10 seconds after the previous
  beat.
- `408` and `425` are retried; every other `4xx` is reported once and not
  retried.
- A `429` suspends for at most an hour.

### Fixed

- Rate-limit suspensions use the monotonic clock, so a wall-clock jump cannot
  stretch or cut them short.
- `Autoposter.close()` closes the HTTP client on Java 21 and newer.
- A token pasted with a leading `Bearer ` works.
- A blank webhook secret is rejected like an empty one.

## [0.1.0] - 2026-10-15

### Added

- `TheDiscordList.autopost(...)` for a `GatewayDiscordClient`: stats,
  per-shard heartbeats and global slash commands, with guild counts tracked
  from READY and guild events and a `cluster(id, count)` override for bots
  split across processes.
- Status handling shared by every publisher: 401 stops everything, 403
  suspends heartbeats for 30 minutes, 404/409 disable one publisher, 429
  suspends stats and commands, transient failures retry with backoff.
- `Webhooks.verify(...)` for inbound webhook signatures.
