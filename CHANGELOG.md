

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] - 2023-mm-dd (this date should be changed on release)

### Added

- Minting status is now displayed in the UI.
- Dependency on `akka-slf4j`.
- New parameters for the launcher:  `--btc-url`, `--btc-port`, `--btc-user`, `--btc-password`, `--zmq-url`, `--zmq-port`. These allow to connect to the Bitcoin node and the ZMQ server.
- Support for monitoring the Bitcoin network.


### Changed

- Default port is now 4000.
- `brambl-cli` version (for IT) is now 2.0.0-beta3.
- Updated BramblSc to 2.0.0-beta4.
- Integration tests to use our own Bitcoin regtest node Docker image.

### Removed

- `/api/confirm-deposit-btc` WS was removed.