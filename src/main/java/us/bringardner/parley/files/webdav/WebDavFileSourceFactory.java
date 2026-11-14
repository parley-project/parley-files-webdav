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

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceUri;
import us.bringardner.parley.files.FileSourceUser;


/**
 * FileSource factory for WebDAV servers over HTTP (RFC 4918; {@link WebDavsFileSourceFactory} for
 * HTTPS), on the JDK's own HTTP client.
 * <p>
 * A connection is a server (host and port), a user and password (HTTP Basic), and a root: the
 * server's path where the WebDAV folder starts (/remote.php/dav/files/tony on Nextcloud, say), which
 * is "/" for the FileSources. HTTP has no session, so connecting checks the settings with a PROPFIND
 * of the root and keeps nothing open but the client's pool of connections.
 * <p>
 * <h2>Locks, atomic create and FileChannel</h2>
 * <ul>
 * <li><b>Locks:</b> {@code PROCESS} scope: this JVM only. WebDAV's own LOCK isn't used, so other
 * clients of the server aren't kept out. Range locks work within the JVM.</li>
 * <li><b>Atomic create:</b> {@code createNewFile()} sends a PUT with {@code If-None-Match: *}, which a
 * server that keeps to HTTP (RFC 9110 section 13.1.2) stores only if nothing is there; not every server
 * does, so the capabilities don't claim it. {@code mkdir()} is atomic (MKCOL fails if something is
 * there).</li>
 * <li><b>FileChannel:</b> a channel for reading uses range requests; one for writing works on a local
 * copy of the file, sent back when it is synced or closed ({@link WebDavLocalCopyIoController}).</li>
 * <li><b>force():</b> sends the local copy back when it changed; what the server does to keep it is
 * its own business.</li>
 * </ul>
 * See parley-files' README ("Locking") for the rules every file system keeps.
 */
public class WebDavFileSourceFactory extends FileSourceFactory {

	private static final long serialVersionUID = 1L;
	public static final String FACTORY_ID = "webdav";
	/**
	 * The start of the JVM-wide defaults for how transfers are tuned (parley.files.webdav.bufferSize,
	 * parley.files.webdav.attributeCacheTtl). A connection's own settings (host, port, user, password ...)
	 * come only from the connection.
	 */
	public static final String SYSTEM_PROPERTY_PREFIX = "parley.files.webdav";
	public static final String PROP_HOST = "host";
	public static final String PROP_PORT = "port";
	public static final String PROP_USER = "user";
	public static final String PROP_PSWD = "password";
	/** The server's path where the WebDAV folder starts; "/" for the FileSources. Default "/". */
	public static final String PROP_ROOT = "root";
	public static final String PROP_TIMEOUT = "timeout";
	/** The webdav type's port (HTTP) */
	public static final int DEFAULT_PORT = 80;
	/** The webdavs type's port (HTTPS) */
	public static final int DEFAULT_TLS_PORT = 443;
	public static final String DEFAULT_ROOT = "/";
	/** How long a connection, or an answer, may take unless set. */
	public static final int DEFAULT_TIMEOUT = 30000;
	/**
	 * How much to move at a time (bytes), see {@link #setBufferSize(int)}.
	 * <p>
	 * This is for programmers: it isn't in {@link #getConnectionSettings()}, so no dialog
	 * shows it (the forms carry properties they don't describe through unchanged), and
	 * {@link #getConnectProperties()} only has it when it was set, so saved connections
	 * keep following the default. The JVM-wide default is the system property
	 * "parley.files.webdav.bufferSize".
	 */
	public static final String PROP_BUFFER_SIZE = "bufferSize";
	public static final int DEFAULT_BUFFER_SIZE = 64*1024;
	public static final int MIN_BUFFER_SIZE = 8*1024;
	public static final int MAX_BUFFER_SIZE = 16*1024*1024;


	private WebDavFileSource currentDirectory;
	/** volatile: isConnected() reads it without the lock */
	private volatile WebDavClient client;
	private String host;
	private int port = -1;
	private String user;
	/** As set: a password or a reference to one (@keychain:...); see {@link #secret(String, String)}. */
	private String passwd;
	private String root = DEFAULT_ROOT;
	private int timeout = DEFAULT_TIMEOUT;
	/** null: the JVM default, else DEFAULT_BUFFER_SIZE. Only a value that was set is saved. */
	private volatile Integer bufferSize;
	private FileSource[] roots;

	public WebDavFileSourceFactory() {
		super();
	}


	// ---- what changed, by path

	/** Counts the changes made through this factory; each is stamped with the count after it. */
	private final java.util.concurrent.atomic.AtomicLong changes = new java.util.concurrent.atomic.AtomicLong();
	/** A path's stamp when the file there was changed (made, written, deleted, renamed ...). */
	private final java.util.concurrent.ConcurrentHashMap<String, Long> entryChanged = new java.util.concurrent.ConcurrentHashMap<>();
	/** A folder's stamp when what is in it changed. */
	private final java.util.concurrent.ConcurrentHashMap<String, Long> listingChanged = new java.util.concurrent.ConcurrentHashMap<>();
	/** The stamp of the last change that could have touched anything. */
	private volatile long everythingChanged;

	/**
	 * The count of changes so far. A FileSource takes it before it asks the server, and what it is
	 * told is good until a change after that touches its path (see {@link #changedSince}).
	 */
	long changeStamp() {
		return changes.get();
	}

	/**
	 * Says a file was changed through this factory (made, written, deleted, renamed): what is known
	 * of it, of anything under it and of its folder's listing is read again next time, and nothing
	 * else.
	 *
	 * @param paths the absolute paths that changed
	 */
	void noteChange(String... paths) {
		long stamp = changes.incrementAndGet();
		for(String path : paths) {
			String p = WebDavFileSource.normalize(path);
			entryChanged.put(p, stamp);
			listingChanged.put(WebDavFileSource.parentOf(p), stamp);
		}
		// FileSourceFactory's count, for anything that watches the factory as a whole
		noteChange();
	}

	/** Says the listing of a folder (as well as the folder itself) must be read again, as a refresh does. */
	void noteListingChange(String dir) {
		long stamp = changes.incrementAndGet();
		String p = WebDavFileSource.normalize(dir);
		entryChanged.put(p, stamp);
		listingChanged.put(p, stamp);
		listingChanged.put(WebDavFileSource.parentOf(p), stamp);
		noteChange();
	}

	/** Says anything may have changed: everything is read again. */
	void noteEverythingChanged() {
		everythingChanged = changes.incrementAndGet();
		noteChange();
	}

	/**
	 * @param path an absolute path
	 * @param stamp the {@link #changeStamp()} taken before what is known of it was read
	 * @param listing true for what is in the folder at path, false for the file itself
	 * @return true if a change since then touched it: the file, a folder it is in (a rename or
	 * delete of a folder changes everything under it), or for a listing, what is in it
	 */
	boolean changedSince(String path, long stamp, boolean listing) {
		if( everythingChanged > stamp ) {
			return true;
		}
		String p = WebDavFileSource.normalize(path);
		if( listing ) {
			Long l = listingChanged.get(p);
			if( l != null && l > stamp ) {
				return true;
			}
		}
		while( p != null ) {
			Long e = entryChanged.get(p);
			if( e != null && e > stamp ) {
				return true;
			}
			p = p.equals("/") ? null : WebDavFileSource.parentOf(p);
		}
		return false;
	}

	// ---- uploads in progress

	/** The uploads this factory has open, by path. */
	private final java.util.concurrent.ConcurrentHashMap<String, WebDavOutputStream> uploads = new java.util.concurrent.ConcurrentHashMap<>();

	/** An upload to a path has started (the last one opened answers for the path). */
	void uploadStarted(String path, WebDavOutputStream out) {
		uploads.put(WebDavFileSource.normalize(path), out);
	}

	/** @return the paths of the uploads this factory has open (for tests and diagnostics) */
	java.util.Set<String> openUploads() {
		return new java.util.TreeSet<>(uploads.keySet());
	}

	/** An upload has ended (stored or not). */
	void uploadEnded(String path, WebDavOutputStream out) {
		uploads.remove(WebDavFileSource.normalize(path), out);
	}

	/**
	 * @return what an upload of this factory that is still open says of the file (there, a file, as
	 *         long as what has been written so far), or null if there is none. A server may hold a
	 *         lock on a file while it is uploaded (Tomcat 10.1 does), so asking it would wait for the
	 *         upload to end, and the code writing it would wait on itself.
	 */
	WebDavFile uploading(String path) {
		String p = WebDavFileSource.normalize(path);
		WebDavOutputStream out = uploads.get(p);
		return out == null ? null : new WebDavFile(p, false, out.getCount(), System.currentTimeMillis(), 0, null, null);
	}

	// ---- recent listings, shared by every FileSource of the factory

	/** How many folder listings are kept for {@link #recentListing(String)}. */
	static final int MAX_RECENT_LISTINGS = 64;

	/** A folder's entries, the stamp taken before they were read and when they were read. */
	static final class RecentListing {
		final WebDavFile[] files;
		final long stamp;
		final long at;

		RecentListing(WebDavFile[] files, long stamp, long at) {
			this.files = files;
			this.stamp = stamp;
			this.at = at;
		}

		/** @return the entry with this name, or null */
		WebDavFile get(String name) {
			for(WebDavFile f : files) {
				if( name.equals(f.getName()) ) {
					return f;
				}
			}
			return null;
		}
	}

	private final java.util.LinkedHashMap<String, RecentListing> recentListings = new java.util.LinkedHashMap<>(16, 0.75f, true) {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<String, RecentListing> eldest) {
			return size() > MAX_RECENT_LISTINGS;
		}
	};

	/**
	 * Keeps a folder's listing, so any FileSource for that folder (or for a file in it) made later
	 * uses it instead of asking the server again while it is trusted.
	 */
	void keepListing(String dir, WebDavFile[] files, long stamp, long at) {
		synchronized (recentListings) {
			recentListings.put(WebDavFileSource.normalize(dir), new RecentListing(files, stamp, at));
		}
	}

	/**
	 * @return the folder's listing if one was read within the attribute cache time and nothing
	 * changed in the folder since, otherwise null
	 */
	RecentListing recentListing(String dir) {
		String p = WebDavFileSource.normalize(dir);
		RecentListing ret;
		synchronized (recentListings) {
			ret = recentListings.get(p);
		}
		if( ret != null && (System.currentTimeMillis() - ret.at > getAttributeCacheTtl()
				|| changedSince(p, ret.stamp, true)) ) {
			synchronized (recentListings) {
				recentListings.remove(p, ret);
			}
			ret = null;
		}
		return ret;
	}


	public int getTimeout() {
		return timeout;
	}

	/** @param timeout ms; takes effect at the next connect */
	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	/** true for HTTPS (the webdavs type) */
	public boolean isSecure() {
		return false;
	}

	/** @return the port when none is given: {@value #DEFAULT_PORT} for webdav, {@value #DEFAULT_TLS_PORT} for webdavs */
	public int defaultPort() {
		return isSecure() ? DEFAULT_TLS_PORT : DEFAULT_PORT;
	}

	/** A relative path is taken from the current directory (the root unless it was set). */
	public FileSource createFileSource(String name) throws IOException {
		String path = name == null ? "" : name.replace('\\', '/');
		if( !path.startsWith("/")) {
			return getCurrentDirectory().getChild(path);
		}
		return new WebDavFileSource(path,this);
	}

	public FileSource createFileSource(String parent, String name) throws IOException {
		return createFileSource((parent == null ? "" : parent)+"/"+name);
	}

	/**
	 * The same server folder and account: same type (HTTP or HTTPS), host (ignoring case), port,
	 * root and user. Paths from such factories name the same files, so isChildOfMine can compare them.
	 */
	@Override
	public boolean isSameFileSystem(FileSourceFactory other) {
		if( other == this ) {
			return true;
		}
		if( !(other instanceof WebDavFileSourceFactory)) {
			return false;
		}
		WebDavFileSourceFactory o = (WebDavFileSourceFactory) other;
		String h1 = getHost(), h2 = o.getHost();
		return isSecure() == o.isSecure()
				&& getPort() == o.getPort()
				&& (h1 == null ? h2 == null : h1.equalsIgnoreCase(h2))
				&& WebDavClient.normalizeRoot(getRoot()).equals(WebDavClient.normalizeRoot(o.getRoot()))
				&& java.util.Objects.equals(getUser(), o.getUser());
	}

	public String getTypeId() {
		return FACTORY_ID;
	}

	/** WebDAV's versioning (RFC 3253) isn't used */
	public boolean isVersionSupported() {
		return false;
	}

	public FileSource[] listRoots() {
		if( roots == null ) {
			roots = new FileSource[1];
			roots[0] = new WebDavFileSource("/", this);
		}
		return roots;
	}

	public void setCurrentDirectory(FileSource dir) {
		this.currentDirectory=(WebDavFileSource)dir;
	}

	/** The root ("/") unless another was set: HTTP has no current directory of its own. */
	public WebDavFileSource getCurrentDirectory() throws IOException {
		if( this.currentDirectory== null ) {
			this.currentDirectory = new WebDavFileSource("/",this);
		}
		return this.currentDirectory;
	}

	/**
	 * The client for this connection, connected if it wasn't.
	 *
	 * @throws IOException if it can't connect
	 */
	WebDavClient getClient() throws IOException {
		WebDavClient c = client;
		if( c == null ) {
			synchronized (this) {
				c = client;
				if( c == null ) {
					c = newClient();
					client = c;
				}
			}
		}
		return c;
	}

	/**
	 * A new client, checked with a PROPFIND of the root: the server answers, takes the login, does
	 * WebDAV there, and the root is a folder.
	 */
	private WebDavClient newClient() throws IOException {
		String h = getHost();
		if( h == null || h.trim().isEmpty() ) {
			throw new IOException("No host to connect to");
		}
		String auth = null;
		String u = getUser();
		if( u != null && !u.isEmpty() ) {
			String pw = getPasswd();
			char[] secret = pw == null || pw.isEmpty() ? new char[0] : secret(PROP_PSWD, pw);
			byte[] login = null;
			try {
				login = (u + ":" + new String(secret)).getBytes(StandardCharsets.UTF_8);
				auth = "Basic " + Base64.getEncoder().encodeToString(login);
			} finally {
				Arrays.fill(secret, '\0');
				if( login != null ) {
					Arrays.fill(login, (byte) 0);
				}
			}
		}
		WebDavClient c = new WebDavClient(isSecure(), h.trim(), getPort(), getRoot(), auth,
				Duration.ofMillis(timeout <= 0 ? DEFAULT_TIMEOUT : timeout));
		boolean ok = false;
		try {
			WebDavFile r = c.stat("/");
			if( r == null ) {
				throw new java.io.FileNotFoundException("No WebDAV folder at " + c.describe() + " (check the root setting)");
			}
			if( !r.isDirectory() ) {
				throw new IOException(c.describe() + " is a file, not a WebDAV folder (check the root setting)");
			}
			ok = true;
			return c;
		} catch (WebDavException e) {
			if( e.getStatus() == 401 ) {
				String msg = "Can't log on to " + c.describe() + " user= " + (u == null || u.isEmpty() ? "(none)" : u)
						+ ": the user or password was refused";
				logError(msg);
				throw new WebDavException(msg, 401);
			}
			throw e;
		} finally {
			if( !ok ) {
				c.close();
			}
		}
	}

	// ---- attribute cache

	/** The system property for {@link #getAttributeCacheTtl()}, like "parley.files.webdav.bufferSize". */
	public static final String SYSTEM_PROPERTY_ATTRIBUTE_CACHE_TTL = SYSTEM_PROPERTY_PREFIX + ".attributeCacheTtl";
	/** What a FileSource remembers of a file or a listing is trusted for this long (ms) unless set. */
	public static final long DEFAULT_ATTRIBUTE_CACHE_TTL = 2000;

	private volatile Long attributeCacheTtl;

	/**
	 * How long (ms) a FileSource trusts what it was told about a file (its attributes) and a
	 * directory (its listing) before it asks the server again. A change made through this factory
	 * is seen at once whatever this is (see {@link #noteChange(String...)}); this is how soon a
	 * change made by someone else is seen. 0 asks every time, like a java.io.File.
	 * <p>
	 * The value set with {@link #setAttributeCacheTtl(long)}, else the system property
	 * parley.files.webdav.attributeCacheTtl (a duration, 2s), else {@link #DEFAULT_ATTRIBUTE_CACHE_TTL}.
	 */
	public long getAttributeCacheTtl() {
		Long set = attributeCacheTtl;
		if( set != null ) {
			return set;
		}
		String v = System.getProperty(SYSTEM_PROPERTY_ATTRIBUTE_CACHE_TTL);
		if( v != null && !v.trim().isEmpty() ) {
			try {
				return us.bringardner.parley.core.util.Durations.parse(v);
			} catch (IllegalArgumentException e) {
				// a bad value uses the default
			}
		}
		return DEFAULT_ATTRIBUTE_CACHE_TTL;
	}

	public void setAttributeCacheTtl(long millis) {
		this.attributeCacheTtl = Math.max(0, millis);
	}

	// ---- buffer size

	private static int clampBufferSize(long size) {
		return (int) Math.max(MIN_BUFFER_SIZE, Math.min(MAX_BUFFER_SIZE, size));
	}

	/** The size that was set, else the system property parley.files.webdav.bufferSize, else null (the default). */
	private Integer configuredBufferSize() {
		Integer ret = bufferSize;
		if( ret == null ) {
			String v = System.getProperty(SYSTEM_PROPERTY_PREFIX+"."+PROP_BUFFER_SIZE);
			if( v != null && !v.trim().isEmpty()) {
				try {
					ret = clampBufferSize(Long.parseLong(v.trim()));
				} catch (NumberFormatException e) {
					// a bad value uses the default
				}
			}
		}
		return ret;
	}

	/**
	 * How much an upload holds before a write waits for the server to take it, how much a download
	 * reads at a time, and a random access stream's chunk: the size set with {@link #setBufferSize(int)}
	 * or {@link #PROP_BUFFER_SIZE}, else the system property parley.files.webdav.bufferSize, else
	 * {@link #DEFAULT_BUFFER_SIZE}.
	 */
	public int getBufferSize() {
		Integer ret = configuredBufferSize();
		return ret == null ? DEFAULT_BUFFER_SIZE : ret;
	}

	/** @param size bytes, kept within {@link #MIN_BUFFER_SIZE} and {@link #MAX_BUFFER_SIZE}; for streams opened after this */
	public void setBufferSize(int size) {
		bufferSize = clampBufferSize(size);
	}

	// ---- connection settings

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public String getPasswd() {
		return passwd;
	}

	public void setPasswd(String passwd) {
		this.passwd = passwd == null || passwd.isEmpty() ? null : passwd;
	}

	public int getPort() {
		return port <= 0 ? defaultPort() : port;
	}

	public void setPort(int port) {
		if( port <= 0 ) {
			port = defaultPort();
		}
		this.port = port;
	}

	public String getUser() {
		return user;
	}

	/** @param user a name, or "name:password"; null or empty for no login */
	public void setUser(String user) {
		if( user == null || user.isEmpty() ) {
			this.user = null;
		} else {
			String [] tmp = user.split(":", 2);
			this.user = tmp[0].isEmpty() ? null : tmp[0];
			if( tmp.length>1) {
				setPasswd(tmp[1]);
			}
		}
	}

	/** @return the server's path where the WebDAV folder starts ("/" unless set) */
	public String getRoot() {
		return root;
	}

	/** @param root the server's path where the WebDAV folder starts; null or empty for "/" */
	public void setRoot(String root) {
		this.root = root == null || root.trim().isEmpty() ? DEFAULT_ROOT : WebDavFileSource.normalize(root.trim());
	}

	@Override
	protected boolean connectImpl() throws IOException {
		synchronized (this) {
			// a new client each time: settings may have changed, and a reference to a password is
			// resolved again (a prompt is asked again)
			WebDavClient old = client;
			client = null;
			if( old != null ) {
				old.close();
			}
		}
		noteEverythingChanged();
		return getClient() != null;
	}

	/** The timeout as a person would write it: 5 s, or 2500 ms. */
	static String describe(Duration d) {
		long ms = d.toMillis();
		return ms % 1000 == 0 ? (ms / 1000)+" s" : ms+" ms";
	}

	@Override
	protected void disConnectImpl() {
		// an upload still open (a stream that wasn't closed) fails: nothing waits for it
		for(java.util.Map.Entry<String, WebDavOutputStream> e : uploads.entrySet()) {
			logError("Disconnected with an upload to "+e.getKey()+" still open: it isn't stored");
			e.getValue().abort(new IOException("Disconnected before the upload to "+e.getKey()+" was closed"));
		}
		uploads.clear();
		entryChanged.clear();
		listingChanged.clear();
		synchronized (recentListings) {
			recentListings.clear();
		}
		noteEverythingChanged();
		WebDavClient c;
		synchronized (this) {
			c = client;
			client = null;
		}
		if( c != null ) {
			c.close();
		}
	}

	@Override
	public boolean isSecretProperty(String name) {
		return PROP_PSWD.equals(name);
	}

	public Properties getConnectProperties() {
		Properties ret = new Properties();
		ret.setProperty(PROP_HOST, host==null?"":host);
		ret.setProperty(PROP_PORT, ""+getPort());
		ret.setProperty(PROP_USER, user==null?"":user);
		ret.setProperty(PROP_PSWD, passwd==null?"":passwd);
		ret.setProperty(PROP_ROOT, root);
		ret.setProperty(PROP_TIMEOUT,us.bringardner.parley.core.util.Durations.format(Math.max(0, timeout)));
		Integer buffer = bufferSize;
		if( buffer != null ) {
			ret.setProperty(PROP_BUFFER_SIZE, ""+buffer);
		}
		return ret;
	}

	/** Only checks: it never connects. HTTP has no session, so this is whether connect() worked. */
	public boolean isConnected() {
		return client != null;
	}

	@Override
	public List<ConnectionSetting> getConnectionSettings() {
		return List.of(
				ConnectionSetting.text(PROP_HOST, "Host").asRequired(),
				ConnectionSetting.integer(PROP_PORT, "Port", 1L, 65535L).withDefault(""+defaultPort()).asRequired(),
				ConnectionSetting.text(PROP_USER, "User"),
				ConnectionSetting.secret(PROP_PSWD, "Password")
					.withDescription(isSecure() ? "Sent with HTTP Basic, over TLS" : "Sent with HTTP Basic, in the clear: use webdavs for a password that matters"),
				ConnectionSetting.text(PROP_ROOT, "Root").withDefault(DEFAULT_ROOT)
					.withDescription("The server's path where the WebDAV folder starts (e.g. /remote.php/dav/files/USER on Nextcloud)"),
				ConnectionSetting.duration(PROP_TIMEOUT, "Timeout").asAdvanced().withDefault("30s"));
	}


	public void setConnectionProperties(URL url) {
		if( url == null ) {
			setHost(null);
			setPort(defaultPort());
			setUser(null);
			setPasswd(null);
			setRoot(null);
		} else {
			try {
				FileSourceUri fsurl = new FileSourceUri(url.toURI());
				setHost(fsurl.getHost());
				setPort(fsurl.getPort());
				setUser( fsurl.getUser());
				setPasswd(fsurl.getPassword());
				String r = fsurl.getQueryByName(PROP_ROOT);
				if( r != null ) {
					setRoot(r);
				}
			} catch (IOException | URISyntaxException e) {
				logError("Can't read the connection from "+FileSourceFactory.safe(String.valueOf(url)), e);
			}
		}
	}

	public void setConnectionProperties(Properties prop) {
		if( prop == null ) {
			setHost(null);
			setUser(null);
			setPasswd(null);
			setPort(defaultPort());
			setRoot(null);
			timeout = DEFAULT_TIMEOUT;
			bufferSize = null;
		} else {
			setHost(prop.getProperty(PROP_HOST));
			int tmp = defaultPort();
			try {
				tmp = Integer.parseInt(prop.getProperty(PROP_PORT,""+defaultPort()).trim());
			} catch (Exception e) {
			}
			setPort(tmp);
			setUser(prop.getProperty(PROP_USER));
			setPasswd(prop.getProperty(PROP_PSWD));
			setRoot(prop.getProperty(PROP_ROOT));
			String t = prop.getProperty(PROP_TIMEOUT);
			if( t != null && !t.trim().isEmpty() ) {
				try {
					timeout = us.bringardner.parley.core.util.Durations.toIntMillis(us.bringardner.parley.core.util.Durations.parse(t));
				} catch (IllegalArgumentException e) {
					logError("Ignoring "+PROP_TIMEOUT+": "+e.getMessage());
				}
			}
			String buffer = prop.getProperty(PROP_BUFFER_SIZE);
			if( buffer != null && !buffer.trim().isEmpty()) {
				// No dialog shows this, so a bad value can't be fixed there and must not stop
				// the connection: say so and keep the size as it is.
				try {
					setBufferSize(clampBufferSize(Long.parseLong(buffer.trim())));
				} catch (NumberFormatException e) {
					logError("Ignoring "+PROP_BUFFER_SIZE+" '"+buffer+"': not a number");
				}
			}
		}
	}

	/**
	 * return an FileSourceFactory with the same configuration but NOT the same connection.
	 */
	@Override
	public FileSourceFactory createThreadSafeCopy() {
		WebDavFileSourceFactory ret = newCopy();
		ret.host = host;
		ret.port = port;
		ret.passwd = passwd;
		ret.user = user;
		ret.root = root;
		ret.timeout = timeout;
		ret.bufferSize = bufferSize;
		ret.attributeCacheTtl = attributeCacheTtl;
		return ret;
	}

	/**
	 * A new, unconfigured factory of this class for createThreadSafeCopy(); a subclass
	 * overrides it so its copies are the same kind (a webdavs factory's copy must stay webdavs).
	 */
	protected WebDavFileSourceFactory newCopy() {
		return new WebDavFileSourceFactory();
	}

	@Override
	public String getTitle() {
		return getTypeId()+"://"+getUser()+"@"+getHost()+":"+getPort()+(DEFAULT_ROOT.equals(root) ? "" : root);
	}

	@Override
	public String getURL() {
		return getTypeId()+"://"+getUser()+":"+getPasswd()+"@"+getHost()+":"+getPort()+(DEFAULT_ROOT.equals(root) ? "" : root);
	}

	@Override
	public char getPathSeparatorChar() {
		return ':';
	}

	@Override
	public char getSeparatorChar() {
		return '/';
	}


	private final FileSourceUser principal = new FileSourceUser() {

		private static final long serialVersionUID = 1L;

		@Override
		public String getName() {
			if( isConnected()) {
				return WebDavFileSourceFactory.this.user;
			} else {
				return "UnKnown";
			}
		}
	};

	@Override
	public FileSourceUser whoAmI() {
		return principal;
	}

	/** WebDAV has no links (RFC 4437's redirect references aren't used) */
	@Override
	public FileSource createSymbolicLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new UnsupportedOperationException();
	}

	/** WebDAV has no links (RFC 4437's bindings aren't used) */
	@Override
	public FileSource createLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new UnsupportedOperationException();
	}
}
