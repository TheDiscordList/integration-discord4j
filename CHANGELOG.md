# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0]

### Added

- `TheDiscordList.autopost(...)` for a `GatewayDiscordClient`: stats,
  per-shard heartbeats and global slash commands, with guild counts tracked
  from READY and guild events and a `cluster(id, count)` override for bots
  split across processes.
- Status handling shared by every publisher: 401 stops everything, 403
  suspends heartbeats for 30 minutes, 404/409 disable one publisher, 429
  suspends stats and commands, transient failures retry with backoff.
- `Webhooks.verify(...)` for inbound webhook signatures.
