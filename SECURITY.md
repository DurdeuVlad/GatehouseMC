# Security Policy

Report security issues privately to the repository maintainers. Do not publish bot tokens, server credentials, database files, or unredacted logs in an issue.

## Important limitation

Raw `online-mode=false` Minecraft profiles are not authenticated identities. This mod records the exact profile Minecraft observed and applies approval to the native whitelist; it cannot prove account ownership.

## Data handled

The v1 workflow stores usernames, offline UUIDs, timestamps, decisions, administrator identifiers, provider message identifiers, audit events, and retry state. It does not store player IP addresses.
