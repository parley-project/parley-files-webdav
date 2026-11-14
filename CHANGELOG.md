# Changelog

## parley-files-webdav 2.0.0 (unreleased)

### Added (2026-11-14: new module, To-Do 17.57)

- **`webdav` and `webdavs` FileSource types** (`WebDavFileSourceFactory`, `WebDavsFileSourceFactory`): WebDAV
  (RFC 4918) over HTTP and HTTPS on the JDK's own HTTP client, with no third-party libraries. Settings `host`,
  `port`, `user`, `password` (HTTP Basic), `root`, `timeout`; `bufferSize` for programmers. Modeled on
  parley-files-ftp. See the README and docs/DESIGN.md.
