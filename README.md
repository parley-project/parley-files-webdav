# parley-files-webdav

A [parley-files](https://github.com/parley-project/parley-files) `FileSource` implementation for
WebDAV servers (RFC 4918) over HTTP and HTTPS, part of **Parley**, a family of Java libraries for
implementing internet protocols. It runs on the JDK's own HTTP client (`java.net.http`) and XML
parser: no third-party libraries.

Put it on the class path and `FileSourceFactory` finds it through `ServiceLoader`
(`WebDavFileSourceFactory`, type `webdav`, and `WebDavsFileSourceFactory`, type `webdavs`);
application code keeps working with `FileSource` and doesn't need to name this module.

Requires Java 11 or later. Depends on `parley-files`.

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-files-webdav</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```

It is Parley's own WebDAV, written after parley-files-vfs's probe of Apache Commons VFS's WebDAV
found it loses failed uploads, holds every upload in memory and lists every folder as its own child
(To-Do 17.1 and 17.57). [What it does about each](#what-went-wrong-elsewhere) is below.

## Connection settings

| Setting | Default | What it is |
|---|---|---|
| `host` | (required) | The server |
| `port` | 80 (`webdav`), 443 (`webdavs`) | |
| `user`, `password` | none | HTTP Basic login, sent with every request. A password may be a reference (`@keychain:...`), resolved at connect. Over `webdav` it goes in the clear: use `webdavs` for a password that matters |
| `root` | `/` | The server's path where the WebDAV folder starts, e.g. `/remote.php/dav/files/USER` on Nextcloud. It is `/` for the FileSources |
| `timeout` | `30s` | How long a connection, or the answer to a request, may take. An upload waits for its answer as long as it takes, but fails if the server takes nothing of it for this long |
| `bufferSize` | 64 KB | For programmers (not shown in dialogs): what an upload holds before a write waits for the server, and a random access stream's chunk. JVM-wide default: `parley.files.webdav.bufferSize` |

HTTP has no session: `connect()` checks the settings with a PROPFIND of the root (the server answers,
takes the login, does WebDAV there, and the root is a folder) and keeps nothing open but the HTTP
client's pool of connections. A refused login is a `WebDavException` with status 401 at connect.

What a FileSource is told about a file or a folder's listing is trusted for 2 seconds
(`setAttributeCacheTtl`, or the system property `parley.files.webdav.attributeCacheTtl`); a change
made through the same factory is seen at once.

## HTTPS

`webdavs` checks the server's certificate as Parley's other clients do: the chain by the application's
trust managers (`SecureBaseObject.setDefaultTrustManagers()`, as the viewers set with a dialog that asks)
when it has set them, else by the JVM's trust store; and the certificate must be for the host connected
to (the JDK client checks the name). A redirect is reported, never followed, so a login is never sent
to another server.

## How the methods map

| FileSource | WebDAV |
|---|---|
| `exists`, `length`, `lastModified`, `creationTime`, `isDirectory` | PROPFIND, Depth 0 (`resourcetype`, `getcontentlength`, `getlastmodified`, `creationdate`, `getetag`, `getcontenttype`) |
| `listFiles` | PROPFIND, Depth 1 |
| `getInputStream(pos)` | GET, with a `Range` from `pos` |
| `getOutputStream()` | PUT, streamed (chunked) as it is written |
| `getOutputStream(true)` (append) | the file is downloaded to a local temporary file and sent again, followed by what is written: WebDAV can't add to a file |
| `createNewFile` | PUT of nothing with `If-None-Match: *` |
| `mkdir`, `mkdirs` | MKCOL (one per missing folder) |
| `delete` | DELETE; false for a folder that isn't empty (WebDAV's DELETE would take everything in it) |
| `renameTo` | MOVE with `Overwrite: F` (false if something is there; `renameTo(dest, true)` replaces a file) |
| `getRandomAccessStream("r")`, `getSeekableInputStream` | GET with a range per chunk |
| `getRandomAccessStream("rw")` | a local copy, sent back (one PUT) on `sync()` and on close if anything was written |

## What is checked

- **Every answer's status.** An upload is stored only if the server says so: `close()` waits for its
  answer and throws a `WebDavException` with the status (403, 409, 413, 423, 507 ...) and what the
  server said. If the server refuses an upload before it has all of it, the next write throws.
- **The stored size.** After an upload the file is looked up again, and an upload the server cut short
  while saying it was stored is an `IOException`.
- **Listings.** A member is a direct child of the folder whose href is under the root: the folder
  itself (every server lists it), anything deeper, anything outside the root, and a member with no
  `resourcetype` in a `200` propstat (or a `404` of its own) are left out. Hrefs may be paths or whole
  URLs, encoded or not.
- **Names.** Every part of a path is percent-encoded (UTF-8): spaces, `%`, `+`, `#`, `?`, `;` and
  non-ASCII letters work.

## What WebDAV doesn't have

- **Permissions, owners, groups:** WebDAV has no permission bits. The owner's read and write are true for a
  file that's there and everything else is false; none can be set. The server decides when a read or write
  is tried.
- **Times:** `getlastmodified` (whole seconds) and `creationdate` are the server's: `setLastModified` and the
  other time setters return false.
- **Links:** none (`getLinkedTo()` is null; `createSymbolicLink` and `createLink` throw
  `UnsupportedOperationException`).
- **Locks:** `PROCESS` scope, this JVM only. WebDAV's LOCK isn't used.
- **Logins:** HTTP Basic only (no Digest, no OAuth).

## Server notes

- **Tomcat 10.1** (the tests' server) answers `If-None-Match: *` with 412 even when nothing is there. When
  that happens with nothing there, the connection stops sending it, and `createNewFile` is a plain PUT
  after the check that nothing is there: not atomic on such a server (the capabilities don't claim it
  anywhere).
- **Tomcat 10.1** also holds a lock on a file while it is uploaded, so asking it about the file waits until
  the upload ends. The factory answers for its own open uploads itself (the file is there, as long as what
  has been written so far), so code that writes a file and looks at it doesn't wait on itself. Another
  client, or another factory, still waits.
- A stream that isn't closed is failed by `disConnect()` (it isn't stored), and disconnect doesn't wait for
  it (on Java 21 and later the HTTP client is shut down; before that its connections close when idle).

## What went wrong elsewhere

parley-files-vfs's probe of Commons VFS 2.10.0's WebDAV (`webdav4`) found, and this module does instead:

| VFS | Here |
|---|---|
| An upload's (PUT's) answer is never checked: a failed upload looks like a success | Every answer is checked, and the stored size too |
| The whole file is held in memory until close | Uploads are streamed through a buffer (`bufferSize`) |
| Every folder lists itself as a child (it compares hrefs with the full URL), so deleting a folder fails | Hrefs are compared as paths under the root |
| A `resourcetype` written over several lines (Tomcat) throws a ClassCastException | The answer is read with a namespace-aware XML parser |
| HTTPS trusts every certificate | The chain and host are checked as Parley does |

## Tests

The tests start Apache Tomcat's `WebdavServlet` (test scope only) on a free port, plain and HTTPS (a
self-signed certificate made with `keytool`), with a filter that plays a server that goes wrong (cuts an
upload short, refuses one with 507, lists phantom members). They run the shared parley-files suites
(FileSource contract, NIO provider, locks) and this module's own. Names with non-ASCII letters are tested
only where the JVM's file name encoding is UTF-8 (a Linux with no locale set has ASCII, and Tomcat can't
store them there).
