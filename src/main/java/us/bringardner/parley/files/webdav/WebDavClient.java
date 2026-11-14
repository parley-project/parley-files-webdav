/**
 * <PRE>
 *
 * Copyright Tony Bringarder 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 * ~version~
 */
package us.bringardner.parley.files.webdav;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import us.bringardner.parley.core.SecureBaseObject;

/**
 * The WebDAV methods a {@link WebDavFileSource} needs (RFC 4918), on the JDK's own HTTP client:
 * PROPFIND, GET (with a range), PUT (streamed), MKCOL, DELETE and MOVE. Every answer's status is
 * checked; what a method needs to know comes back as a value, anything else as an IOException
 * that names the method, the path and what the server said.
 * <p>
 * Paths are the FileSource's ("/" is the root setting's folder); each part is percent-encoded
 * (UTF-8) on the way out and decoded on the way back. One client per connection: the JDK client
 * keeps its own pool of connections and is safe for any number of threads.
 */
final class WebDavClient {

	/** What a stat or listing asks for (RFC 4918 section 15). */
	private static final byte[] PROPFIND_BODY = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
			+ "<D:propfind xmlns:D=\"DAV:\"><D:prop>"
			+ "<D:resourcetype/><D:getcontentlength/><D:getlastmodified/><D:creationdate/>"
			+ "<D:getetag/><D:getcontenttype/>"
			+ "</D:prop></D:propfind>").getBytes(StandardCharsets.UTF_8);
	private static final String DAV = "DAV:";
	/** How much of an error answer's body is put in a message. */
	private static final int MAX_ERROR_TEXT = 200;

	private final HttpClient http;
	private final boolean https;
	private final String host;
	private final int port;
	/** The root setting, decoded: "" for the server's "/", else "/a/b" with no "/" at the end. */
	private final String root;
	/** "Basic ..." or null */
	private final String authorization;
	private final Duration timeout;
	/**
	 * true once the server has answered "If-None-Match: *" with 412 for a path where nothing is
	 * (Tomcat 10.1 does): it isn't sent again on this connection.
	 */
	private volatile boolean ifNoneMatchMisread;

	/**
	 * @param https true for HTTPS: the server's certificate is checked by the application's trust
	 *        managers ({@link SecureBaseObject#getDefaultTrustManagers()}) when it has set them, else
	 *        by the JVM's, and must be for HOST (the JDK client checks the name)
	 * @param root the server's path where the FileSource's "/" is
	 * @param authorization the Authorization header to send with every request, or null
	 * @param timeout how long a connection, or an answer to a request, may take
	 */
	WebDavClient(boolean https, String host, int port, String root, String authorization, Duration timeout) throws IOException {
		this.https = https;
		this.host = host;
		this.port = port;
		this.root = normalizeRoot(root);
		this.authorization = authorization;
		this.timeout = timeout;
		HttpClient.Builder b = HttpClient.newBuilder()
				// HTTP/1.1: what every WebDAV server speaks; HTTP/2 brings nothing for files
				.version(HttpClient.Version.HTTP_1_1)
				// a redirect is reported, not followed: a WebDAV method must not change into a GET or
				// go to another server with the password
				.followRedirects(HttpClient.Redirect.NEVER)
				.proxy(ProxySelector.getDefault())
				.connectTimeout(timeout);
		if( https ) {
			b.sslContext(sslContext());
		}
		http = b.build();
	}

	/** The application's trust managers' first X509 one, else the JVM's, in a TLS context. */
	private static SSLContext sslContext() throws IOException {
		try {
			TrustManager[] set = SecureBaseObject.getDefaultTrustManagers();
			if( first(set) == null ) {
				TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				f.init((KeyStore) null);
				set = f.getTrustManagers();
			}
			SSLContext ret = SSLContext.getInstance("TLS");
			// the JDK's HTTP client checks the host name against the certificate itself (HTTPS
			// endpoint identification), whatever trust manager is used
			ret.init(null, new TrustManager[] { first(set) }, null);
			return ret;
		} catch (GeneralSecurityException e) {
			throw new IOException("Can't set up TLS: " + e.getMessage(), e);
		}
	}

	private static X509TrustManager first(TrustManager[] tms) {
		if( tms != null ) {
			for (TrustManager tm : tms) {
				if( tm instanceof X509TrustManager ) {
					return (X509TrustManager) tm;
				}
			}
		}
		return null;
	}

	/**
	 * Closes the connections it keeps and stops what is still going on (a stream someone didn't
	 * close), without waiting for it: HttpClient.shutdownNow() on Java 21 and later. Before that the
	 * client has no close, and its connections close when they have been idle a while.
	 */
	void close() {
		try {
			HttpClient.class.getMethod("shutdownNow").invoke(http);
		} catch (ReflectiveOperationException | RuntimeException e) {
			// Java 11 to 20: nothing to call
		}
	}

	// ------------------------------------------------------------------ paths

	static String normalizeRoot(String root) {
		String r = WebDavFileSource.normalize(root);
		return r.equals("/") ? "" : r;
	}

	/** The server's path for a FileSource path: the root and the path, decoded. */
	String serverPath(String path) {
		String p = WebDavFileSource.normalize(path);
		return p.equals("/") ? (root.isEmpty() ? "/" : root) : root + p;
	}

	/**
	 * The URI of a path; a collection's ends with "/", as servers list them (a collection asked for
	 * without it is redirected by some).
	 */
	URI uri(String path, boolean collection) {
		String p = serverPath(path);
		if( collection && !p.endsWith("/") ) {
			p = p + "/";
		}
		String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
		return URI.create((https ? "https" : "http") + "://" + h + ":" + port + encodePath(p));
	}

	/** Percent-encodes everything in a path but the unreserved characters and "/" (RFC 3986). */
	static String encodePath(String path) {
		StringBuilder ret = new StringBuilder(path.length() + 16);
		for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xff;
			if( (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
					|| c == '-' || c == '.' || c == '_' || c == '~' || c == '/' ) {
				ret.append((char) c);
			} else {
				ret.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
						.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
			}
		}
		return ret.toString();
	}

	/**
	 * Decodes %XX (UTF-8). Anything else is kept as it is, so an href a server didn't encode (a
	 * space, a non-ASCII letter) is read as written, and "+" stays "+" (it is no space in a path).
	 */
	static String decodePath(String path) {
		if( path.indexOf('%') < 0 ) {
			return path;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(path.length());
		int i = 0;
		while( i < path.length() ) {
			char c = path.charAt(i);
			if( c == '%' && i + 2 < path.length()
					&& Character.digit(path.charAt(i + 1), 16) >= 0 && Character.digit(path.charAt(i + 2), 16) >= 0 ) {
				out.write(Character.digit(path.charAt(i + 1), 16) * 16 + Character.digit(path.charAt(i + 2), 16));
				i += 3;
			} else {
				byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
				if( Character.isHighSurrogate(c) && i + 1 < path.length() ) {
					b = path.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
					i++;
				}
				out.write(b, 0, b.length);
				i++;
			}
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	/**
	 * The FileSource path an href names, or null if it is outside the root (a server may list
	 * anything). An href is a path or a whole URL (RFC 4918 section 8.3).
	 */
	String pathOfHref(String href) {
		String h = href.trim();
		int scheme = h.indexOf("://");
		if( scheme > 0 && scheme < 10 ) {
			int slash = h.indexOf('/', scheme + 3);
			h = slash < 0 ? "/" : h.substring(slash);
		}
		int q = h.indexOf('?');
		if( q >= 0 ) {
			h = h.substring(0, q);
		}
		String p = WebDavFileSource.normalize(decodePath(h));
		if( root.isEmpty() ) {
			return p;
		}
		if( p.equals(root) ) {
			return "/";
		}
		if( p.startsWith(root + "/") ) {
			return p.substring(root.length());
		}
		return null;
	}

	// ------------------------------------------------------------------ requests

	private HttpRequest.Builder request(URI uri, boolean timed) {
		HttpRequest.Builder b = HttpRequest.newBuilder(uri);
		if( timed ) {
			// a request with a body to stream (a PUT) has none: the timeout counts until the answer,
			// which comes after the whole body
			b.timeout(timeout);
		}
		if( authorization != null ) {
			b.header("Authorization", authorization);
		}
		return b;
	}

	private <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> handler) throws IOException {
		try {
			return http.send(r, handler);
		} catch (HttpTimeoutException e) {
			java.net.SocketTimeoutException t = new java.net.SocketTimeoutException("No answer from " + host + ":" + port
					+ " within " + WebDavFileSourceFactory.describe(timeout) + " (timeout) to " + r.method() + " " + r.uri().getRawPath());
			t.initCause(e);
			throw t;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			InterruptedIOException ie = new InterruptedIOException(r.method() + " " + r.uri().getRawPath() + " was interrupted");
			ie.initCause(e);
			throw ie;
		}
	}

	/** An IOException for an answer that wasn't expected: the method, path, status and what the server said. */
	static IOException failure(String method, String path, int status, byte[] body) {
		String text = body == null ? "" : new String(body, 0, Math.min(body.length, 4096), StandardCharsets.UTF_8)
				.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
		if( text.length() > MAX_ERROR_TEXT ) {
			text = text.substring(0, MAX_ERROR_TEXT) + "...";
		}
		String msg = method + " " + path + " failed: " + status + " " + reason(status) + (text.isEmpty() ? "" : " (" + text + ")");
		if( status == 404 || status == 410 ) {
			return new FileNotFoundException(msg);
		}
		return new WebDavException(msg, status);
	}

	static String reason(int status) {
		switch (status) {
		case 301: case 302: case 303: case 307: case 308: return "Redirected";
		case 400: return "Bad Request";
		case 401: return "Unauthorized (the user or password was refused)";
		case 403: return "Forbidden";
		case 404: return "Not Found";
		case 405: return "Method Not Allowed";
		case 409: return "Conflict";
		case 410: return "Gone";
		case 412: return "Precondition Failed";
		case 413: return "Payload Too Large";
		case 415: return "Unsupported Media Type";
		case 416: return "Range Not Satisfiable";
		case 423: return "Locked";
		case 424: return "Failed Dependency";
		case 500: return "Internal Server Error";
		case 501: return "Not Implemented";
		case 502: return "Bad Gateway";
		case 503: return "Service Unavailable";
		case 507: return "Insufficient Storage";
		default: return "";
		}
	}

	/** An unexpected answer with a body: the body is read (a little of it) for the message. */
	private static IOException failure(HttpRequest r, HttpResponse<?> res, String path) {
		Object body = res.body();
		byte[] b = null;
		if( body instanceof byte[] ) {
			b = (byte[]) body;
		} else if( body instanceof String ) {
			b = ((String) body).getBytes(StandardCharsets.UTF_8);
		} else if( body instanceof InputStream ) {
			try (InputStream in = (InputStream) body) {
				b = in.readNBytes(4096);
			} catch (IOException e) {
				// the status is enough
			}
		}
		IOException ret = failure(r.method(), path, res.statusCode(), b);
		if( res.statusCode() / 100 == 3 ) {
			String to = res.headers().firstValue("Location").orElse("");
			ret = new WebDavException(ret.getMessage() + " to " + to
					+ ": redirects aren't followed; check the host, port and root settings", res.statusCode());
		}
		return ret;
	}

	// ------------------------------------------------------------------ PROPFIND

	/**
	 * PROPFIND with Depth 0: the resource, or null if there's none.
	 */
	WebDavFile stat(String path) throws IOException {
		List<WebDavFile> ret = propfind(path, false, 0);
		if( ret == null ) {
			return null;
		}
		String want = WebDavFileSource.normalize(path);
		for (WebDavFile f : ret) {
			if( f.getPath().equals(want) ) {
				return f;
			}
		}
		// a server that answered for the resource with another href (a redirect inside, an alias):
		// its only answer is the resource's
		return ret.size() == 1 ? new WebDavFile(want, ret.get(0).isDirectory(), ret.get(0).getLength(),
				ret.get(0).getLastModified(), ret.get(0).getCreated(), ret.get(0).getEtag(), ret.get(0).getContentType()) : null;
	}

	/**
	 * PROPFIND with Depth 1: what is directly in the folder; null if there's no folder there. The
	 * folder itself, anything not directly in it, and anything the server lists that it has no
	 * resource type for (a member that isn't there) are left out.
	 */
	List<WebDavFile> list(String dir) throws IOException {
		String want = WebDavFileSource.normalize(dir);
		List<WebDavFile> all = propfind(want, true, 1);
		if( all == null ) {
			return null;
		}
		List<WebDavFile> ret = new ArrayList<>(all.size());
		for (WebDavFile f : all) {
			String p = f.getPath();
			if( !p.equals(want) && WebDavFileSource.parentOf(p).equals(want) ) {
				ret.add(f);
			}
		}
		return ret;
	}

	/** null for 404; the answer's resources (with a resource type) otherwise. */
	private List<WebDavFile> propfind(String path, boolean collection, int depth) throws IOException {
		URI uri = uri(path, collection);
		HttpRequest r = request(uri, true)
				.method("PROPFIND", BodyPublishers.ofByteArray(PROPFIND_BODY))
				.header("Depth", "" + depth)
				.header("Content-Type", "application/xml; charset=utf-8")
				.build();
		HttpResponse<byte[]> res = send(r, BodyHandlers.ofByteArray());
		int status = res.statusCode();
		if( status == 404 || status == 410 ) {
			return null;
		}
		if( status != 207 ) {
			if( status == 405 || status == 501 ) {
				throw new WebDavException("PROPFIND " + uri.getRawPath() + " failed: " + status + " " + reason(status)
						+ ": the server doesn't do WebDAV there (check the root setting)", status);
			}
			throw failure(r, res, uri.getRawPath());
		}
		return parse(res.body(), uri.getRawPath());
	}

	/** Reads a 207 Multi-Status answer (RFC 4918 section 13). */
	List<WebDavFile> parse(byte[] xml, String what) throws IOException {
		Document doc;
		try {
			doc = parser().parse(new ByteArrayInputStream(xml));
		} catch (SAXException e) {
			throw new IOException("Can't read the PROPFIND answer for " + what + ": " + e.getMessage(), e);
		}
		List<WebDavFile> ret = new ArrayList<>();
		for (Element response : children(doc.getDocumentElement(), "response")) {
			Element href = child(response, "href");
			if( href == null ) {
				continue;
			}
			// a response with a status of its own (no propstat) is about the resource as a whole
			Element status = child(response, "status");
			if( status != null && !isOk(text(status)) ) {
				continue;
			}
			String path = pathOfHref(text(href));
			if( path == null ) {
				continue;
			}
			boolean typed = false;
			boolean dir = false;
			long length = 0;
			long modified = 0;
			long created = 0;
			String etag = null;
			String contentType = null;
			for (Element propstat : children(response, "propstat")) {
				Element s = child(propstat, "status");
				if( s == null || !isOk(text(s)) ) {
					continue;
				}
				Element prop = child(propstat, "prop");
				if( prop == null ) {
					continue;
				}
				Element type = child(prop, "resourcetype");
				if( type != null ) {
					typed = true;
					dir = child(type, "collection") != null;
				}
				Element e = child(prop, "getcontentlength");
				if( e != null ) {
					try {
						length = Long.parseLong(text(e).trim());
					} catch (NumberFormatException ex) {
						// unknown
					}
				}
				e = child(prop, "getlastmodified");
				if( e != null ) {
					modified = parseDate(text(e));
				}
				e = child(prop, "creationdate");
				if( e != null ) {
					created = parseDate(text(e));
				}
				e = child(prop, "getetag");
				if( e != null ) {
					etag = text(e).trim();
				}
				e = child(prop, "getcontenttype");
				if( e != null ) {
					contentType = text(e).trim();
				}
			}
			if( typed ) {
				// every WebDAV resource has a resource type: one listed without it isn't there
				ret.add(new WebDavFile(path, dir, length, modified, created, etag, contentType));
			}
		}
		return ret;
	}

	/** "HTTP/1.1 200 OK" and the like: a 2xx status. */
	private static boolean isOk(String statusLine) {
		String[] parts = statusLine.trim().split("\\s+");
		return parts.length >= 2 && parts[1].startsWith("2");
	}

	/** RFC 1123 (getlastmodified) or RFC 3339 (creationdate), as servers write either in either. */
	static long parseDate(String text) {
		String t = text.trim();
		if( t.isEmpty() ) {
			return 0;
		}
		try {
			return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
		} catch (DateTimeParseException e) {
			// try the next
		}
		try {
			return OffsetDateTime.parse(t).toInstant().toEpochMilli();
		} catch (DateTimeParseException e) {
			// try the next
		}
		try {
			return Instant.parse(t).toEpochMilli();
		} catch (DateTimeParseException e) {
			return 0;
		}
	}

	/** A namespace-aware parser that reads no DTD and no external entity (XXE). */
	private static DocumentBuilder parser() throws IOException {
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setNamespaceAware(true);
			f.setXIncludeAware(false);
			f.setExpandEntityReferences(false);
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setFeature("http://xml.org/sax/features/external-general-entities", false);
			f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			DocumentBuilder ret = f.newDocumentBuilder();
			ret.setErrorHandler(null);
			return ret;
		} catch (ParserConfigurationException | IllegalArgumentException e) {
			throw new IOException("Can't set up an XML parser: " + e.getMessage(), e);
		}
	}

	private static List<Element> children(Element parent, String name) {
		List<Element> ret = new ArrayList<>();
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if( n instanceof Element && DAV.equals(n.getNamespaceURI()) && name.equals(n.getLocalName()) ) {
				ret.add((Element) n);
			}
		}
		return ret;
	}

	private static Element child(Element parent, String name) {
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if( n instanceof Element && DAV.equals(n.getNamespaceURI()) && name.equals(n.getLocalName()) ) {
				return (Element) n;
			}
		}
		return null;
	}

	private static String text(Element e) {
		String t = e.getTextContent();
		return t == null ? "" : t;
	}

	// ------------------------------------------------------------------ GET

	/**
	 * GET, from START (0 for all of it) to END inclusive (-1 for the end of the file). A server that
	 * ignores the range sends the whole file: what is before START is skipped. A range that starts at
	 * or past the end is an empty stream.
	 *
	 * @throws FileNotFoundException if there's nothing there
	 */
	InputStream get(String path, long start, long end) throws IOException {
		URI uri = uri(path, false);
		HttpRequest.Builder b = request(uri, true).GET();
		boolean ranged = start > 0 || end >= 0;
		if( ranged ) {
			b.header("Range", "bytes=" + start + "-" + (end >= 0 ? "" + end : ""));
		}
		HttpRequest r = b.build();
		HttpResponse<InputStream> res = send(r, BodyHandlers.ofInputStream());
		int status = res.statusCode();
		if( status == 416 ) {
			res.body().close();
			return InputStream.nullInputStream();
		}
		if( status == 206 ) {
			return res.body();
		}
		if( status == 200 ) {
			InputStream in = res.body();
			if( start > 0 ) {
				// the range was ignored: skip to it
				try {
					long left = start;
					while( left > 0 ) {
						long n = in.skip(left);
						if( n <= 0 ) {
							if( in.read() < 0 ) {
								break;
							}
							n = 1;
						}
						left -= n;
					}
				} catch (IOException | RuntimeException e) {
					in.close();
					throw e;
				}
			}
			if( end >= 0 ) {
				return new Limited(in, end - start + 1);
			}
			return in;
		}
		throw failure(r, res, uri.getRawPath());
	}

	/** Stops after a number of bytes (a range a server didn't keep to). */
	private static final class Limited extends java.io.FilterInputStream {
		private long left;

		Limited(InputStream in, long left) {
			super(in);
			this.left = left;
		}

		@Override
		public int read() throws IOException {
			if( left <= 0 ) {
				return -1;
			}
			int ret = in.read();
			if( ret >= 0 ) {
				left--;
			}
			return ret;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if( left <= 0 ) {
				return -1;
			}
			int ret = in.read(b, off, (int) Math.min(len, left));
			if( ret > 0 ) {
				left -= ret;
			}
			return ret;
		}
	}

	// ------------------------------------------------------------------ PUT

	/**
	 * PUT, streamed: the body is what is written to the stream it returns, sent as it is written
	 * (chunked), and the server's answer is checked when it is closed. Nothing is held in memory but
	 * a buffer.
	 *
	 * @param bufferSize bytes the stream holds before a write waits for the server to take them
	 * @param ifNoneMatch true to send "If-None-Match: *": the server stores it only if nothing is there
	 */
	WebDavOutputStream put(String path, int bufferSize, boolean ifNoneMatch) throws IOException {
		URI uri = uri(path, false);
		WebDavOutputStream.Pipe pipe = new WebDavOutputStream.Pipe(bufferSize, timeout);
		HttpRequest.Builder b = request(uri, false)
				.PUT(BodyPublishers.ofInputStream(pipe::reader))
				.header("Content-Type", "application/octet-stream");
		if( ifNoneMatch ) {
			b.header("If-None-Match", "*");
		}
		HttpRequest r = b.build();
		CompletableFuture<HttpResponse<byte[]>> answer = http.sendAsync(r, BodyHandlers.ofByteArray());
		return new WebDavOutputStream(pipe, answer, r.method(), uri.getRawPath());
	}

	/**
	 * A new empty file: a PUT of no bytes with "If-None-Match: *", so a server that keeps to HTTP
	 * (RFC 9110 section 13.1.2) stores it only if nothing is there. A server that answers 412 with
	 * nothing there misreads the condition: it is asked again without it (the caller has just found
	 * nothing there), and the condition isn't sent again on this connection.
	 *
	 * @return true if it was created, false if something was already there
	 */
	boolean createEmpty(String path) throws IOException {
		if( !ifNoneMatchMisread ) {
			if( putEmpty(path, true) ) {
				return true;
			}
			if( stat(path) != null ) {
				return false;
			}
			ifNoneMatchMisread = true;
		}
		return putEmpty(path, false);
	}

	/** @return whether the server misreads "If-None-Match: *" (see {@link #createEmpty(String)}) */
	boolean misreadsIfNoneMatch() {
		return ifNoneMatchMisread;
	}

	/** PUT of no bytes; false for 412 */
	private boolean putEmpty(String path, boolean ifNoneMatch) throws IOException {
		URI uri = uri(path, false);
		HttpRequest.Builder b = request(uri, true)
				.PUT(BodyPublishers.noBody())
				.header("Content-Type", "application/octet-stream");
		if( ifNoneMatch ) {
			b.header("If-None-Match", "*");
		}
		HttpRequest r = b.build();
		HttpResponse<byte[]> res = send(r, BodyHandlers.ofByteArray());
		int status = res.statusCode();
		if( status == 412 ) {
			return false;
		}
		if( status / 100 == 2 ) {
			return true;
		}
		throw failure(r, res, uri.getRawPath());
	}

	/** PUT of a whole body whose length is known. */
	void put(String path, BodyPublisher body) throws IOException {
		URI uri = uri(path, false);
		HttpRequest r = request(uri, false)
				.PUT(body)
				.header("Content-Type", "application/octet-stream")
				.build();
		HttpResponse<byte[]> res = send(r, BodyHandlers.ofByteArray());
		if( res.statusCode() / 100 != 2 ) {
			throw failure(r, res, uri.getRawPath());
		}
	}

	// ------------------------------------------------------------------ MKCOL, DELETE, MOVE

	/**
	 * MKCOL (RFC 4918 section 9.3): one folder.
	 *
	 * @return true if it was made; false if something is already there (405) or its parent isn't (409)
	 */
	boolean mkcol(String path) throws IOException {
		URI uri = uri(path, true);
		HttpRequest r = request(uri, true).method("MKCOL", BodyPublishers.noBody()).build();
		HttpResponse<byte[]> res = send(r, BodyHandlers.ofByteArray());
		int status = res.statusCode();
		if( status == 201 || status == 200 || status == 204 ) {
			return true;
		}
		if( status == 405 || status == 409 ) {
			return false;
		}
		throw failure(r, res, uri.getRawPath());
	}

	/**
	 * DELETE. A folder is deleted with everything in it (RFC 4918 section 9.6): the caller checks
	 * that it's empty first.
	 *
	 * @return true if it was deleted, false if there was nothing there
	 */
	boolean delete(String path, boolean collection) throws IOException {
		URI uri = uri(path, collection);
		HttpRequest r = request(uri, true).DELETE().build();
		HttpResponse<byte[]> res = send(r, BodyHandlers.ofByteArray());
		int status = res.statusCode();
		if( status == 204 || status == 200 || status == 202 ) {
			return true;
		}
		if( status == 404 || status == 410 ) {
			return false;
		}
		// 207: some of it couldn't be deleted
		throw failure(r, res, uri.getRawPath());
	}

	/**
	 * MOVE with "Overwrite: F" (RFC 4918 section 9.9): nothing already at TO is replaced.
	 *
	 * @return true if it was moved, false if something is at TO (412)
	 */
	boolean move(String from, String to, boolean collection) throws IOException {
		URI uri = uri(from, collection);
		HttpRequest r = request(uri, true)
				.method("MOVE", BodyPublishers.noBody())
				.header("Destination", uri(to, collection).toASCIIString())
				.header("Overwrite", "F")
				.build();
		HttpResponse<byte[]> res = send(r, BodyHandlers.ofByteArray());
		int status = res.statusCode();
		if( status == 201 || status == 204 || status == 200 ) {
			return true;
		}
		if( status == 412 ) {
			return false;
		}
		throw failure(r, res, uri.getRawPath());
	}

	/** The server and root, for messages. */
	String describe() {
		return (https ? "https" : "http") + "://" + host + ":" + port + (root.isEmpty() ? "/" : root);
	}
}
