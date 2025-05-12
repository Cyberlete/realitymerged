# 8. NET L1 responsibility and L0 interconnection

Date: 2022-01-05

## Status

Accepted

## Context

NET L1 responsibility and communication between NET L1 and L0 needs to be
specified.

## Decision

NET Layer 1 returns NET blocks as an output and it doesn't create snapshots.
NET Layer 1 sends NET blocks to the Layer 0 so then the Layer 0 can create a Global
snapshot.

Cross-chain swaps will take place on the Layer 0, so then Layer 0 can create
additional NET transactions and blocks.

NET Layer 1 will subscribe to Layer 0, to pull Global snapshots and other data like selected facilitators. If there is a
difference between accepted NET blocks and NET blocks from Global snapshot, NET
Layer 1 will apply missing blocks. In the case of conflicting transactions,
redownload algorithm will be triggered, to reapply data and converge with the Global snapshot.

## Consequences
Communication between L1 and L0 will be bi-directional in a Push/Pull way.
