# parley-files-webdav: design

To-Do 17.57 (decided 2026-11-14 12:46 ET): WebDAV as Parley's own FileSource, on the JDK's HTTP client
(no third-party jars), streaming uploads, every reply checked, reading at a position with range requests,
certificates checked as Parley does. Built 2026-11-14 with parley-files-ftp as the model.

The To-Do said the design was to be discussed before building. These choices were made while building
and are **for review**; each is easy to change.

## 1. Shape (from parley-files-ftp)

| parley-files-ftp | parley-files-webdav |
|---|---|
| `FtpFileSourceFactory` / `FtpsFileSourceFactory` (types `ftp`, `ftps`) | `WebDavFileSourceFactory` / `WebDavsFileSourceFactory` (types `webdav`, `webdavs`) |
| `FtpFileSource` | `WebDavFileSource` |
| `FtpFile` (a listing line) | `WebDavFile` (a PROPFIND response) |
| parley-ftp's `FtpClient` | `WebDavClient` (package private: the WebDAV methods on `java.net.http.HttpClient`) |
| `FtpRandomAccessIoController` | `WebDavRandomAccessIoController` ("r") and `WebDavLocalCopyIoController` ("rw") |
| `FtpSeekableInputStream` | `WebDavSeekableInputStream` |
| `ftp.Handler`, `ftps.Handler` | `webdav.Handler`, `webdavs.Handler` |

Kept from the FTP model: the change stamps and recent listings shared by a factory's FileSources, the
attribute cache time (2 s, `parley.files.webdav.attributeCacheTtl`), the programmers-only `bufferSize`,
password references resolved at connect, `createThreadSafeCopy` through `newCopy()`, java.io.File's answers
(false, null) where FTP gives them.

## 2. Decisions made while building (for review)

- **D1. Type ids `webdav` and `webdavs`**, two factories as `ftp`/`ftps` (HTTPS is a type, not a setting).
  No clash with parley-files-vfs's `vfswebdav`/`vfswebdavs`.
- **D2. Settings:** `host`, `port` (80/443), `user`, `password`, `root` (default `/`), `timeout` (default
  30 s; FTP's 4 s is too short for an HTTP answer such as a large listing), plus `bufferSize`. `root` is the
  server's path of the WebDAV folder (Nextcloud's `/remote.php/dav/files/USER`); the name matches
  vfsfile's `root` setting.
- **D3. HTTP Basic only**, sent with every request (no 401 round trip first). The JDK client's
  `Authenticator` isn't used: it does Basic only anyway, and retries in ways that are hard to report. No
  Digest for now.
- **D4. Redirects are reported, not followed.** A WebDAV method must not turn into a GET, and a login must
  not go to another server. The message names the Location.
- **D5. Uploads are streamed (chunked)** through a bounded buffer between the writer and the HTTP client
  (`WebDavOutputStream.Pipe`). `close()` waits for the answer; an early answer fails the next write. A PUT
  has no request timeout (it counts until the answer, which comes after the whole body); instead a write
  fails if the server takes nothing for `timeout`.
- **D6. Every upload's stored size is checked** with a PROPFIND after the server says it is stored (one
  more request per upload). An upload a server cut short is an IOException.
- **D7. Append = download and send again.** WebDAV has no append. The old content goes to a local
  temporary file first (a server may replace the file as soon as a PUT to it starts, and Tomcat holds a
  lock on it meanwhile, so it can't be read while it is written), then one PUT sends it followed by the
  new bytes. (parley-files-vfs's `vfswebdav` refuses
  append instead.)
- **D8. Random access:** "r" reads chunks with range requests (as FTP's REST/RANG); "rw", "rws", "rwd" work
  on a local copy sent back whole on `sync()` and on close if anything was written. WebDAV can't write part
  of a file (Content-Range on PUT is non-standard and RFC 9110 says servers should refuse it), and a whole
  PUT per chunk written would be far slower. The temporary files are `Files.createTempFile`, as
  `FtpRandomAccessIoController` uses.
- **D9. `delete()` of a folder that isn't empty is false**, as java.io.File: WebDAV's DELETE would take
  everything in it.
- **D10. `renameTo` is MOVE with `Overwrite: F`**: false if something is there. `renameTo(dest, true)`
  (FileSource's default) replaces a file.
- **D11. `createNewFile` is a PUT with `If-None-Match: *`**; the capabilities don't claim it is atomic,
  since servers differ (see 3.1).
- **D12. No permissions, owners, links, settable times or WebDAV LOCK.** Owner read/write true for a file
  that's there, everything else false; locks `PROCESS` scope. RFC 3744 ACLs, RFC 4437 redirect references
  and WebDAV LOCK could come later.
- **D13. Listings** (the VFS bug): members are matched by path under the root, not by URL; the folder
  itself, deeper paths, paths outside the root, and members with no `resourcetype` in a `200` propstat are
  left out.
- **D14. The XML parser** is the JDK's, namespace aware, with DTDs and external entities off (XXE).
- **D15. Proxies:** the JVM's `ProxySelector` (`http.proxyHost`, `https.proxyHost`, `http.nonProxyHosts`), as
  `URLConnection` uses.
- **D16. HTTP/1.1.** HTTP/2 brings nothing for files, and every WebDAV server speaks 1.1.
- **D17. Disconnect fails uploads left open and doesn't wait** (`HttpClient.shutdownNow()` on Java 21+).

## 3. Server behaviour found

### 3.1 Tomcat 10.1 misreads `If-None-Match: *` on PUT

Tomcat 10.1.34's WebdavServlet answers a PUT with `If-None-Match: *` with 412 even when nothing is at the
path (RFC 9110 section 13.1.2: the condition is false only if there is a current representation). So when
a 412 comes with nothing there, the connection stops sending the condition, and `createNewFile` is a plain
PUT after its check that nothing is there.

### 3.2 Tomcat 10.1 locks a file while it is uploaded

A PROPFIND of a file (or a listing of its folder) waits while a PUT to it is in progress. With a streamed
upload, code that opened a stream and then asked about the same file waited on itself until the timeout
(the shared lock suite's APPEND channel did). The factory now answers for its own open uploads (the file is
there, a file, as long as what was written so far) without asking. Another client still waits.

### 3.3 File names on the test server

Tomcat stores a file under the name it was sent, in the JVM's file name encoding; on a Linux with no
locale set that is ASCII, and a non-ASCII name is refused (409). The tests try such names only where the
encoding is UTF-8.
