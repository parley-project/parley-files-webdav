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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.servlets.WebdavServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.files.test.FileSourceTestSupport;
import us.bringardner.parley.files.test.TestServerController;

/**
 * A WebDAV server for the tests: Tomcat's WebdavServlet on a local folder, read and write, on a free
 * port, plain or HTTPS, at a context path ("" for the server's root). Tomcat writes its PROPFIND
 * answers over several lines, which VFS couldn't read (parley-files-vfs).
 * <p>
 * A filter in front of it asks for a Basic login when a user is set, and plays a server that goes
 * wrong: an upload to ".../short/..." keeps only half of what was sent but says it was stored; one
 * to ".../full/..." is refused with 507 Insufficient Storage after the body is read; a PROPFIND of
 * ".../phantom" lists members that aren't there or aren't its own.
 */
final class WebDavTestServer implements TestServerController {

	static final String HOST = "127.0.0.1";
	private static final AtomicInteger COUNT = new AtomicInteger();
	/** Kept, so the level set on it stays. */
	private static final Logger TOMCAT_LOG = Logger.getLogger("org.apache");

	private final Path home;
	private final Path keyStore;
	private final String keyStorePassword;
	/** "" or "/dav" ... */
	private final String contextPath;
	private volatile String user;
	private volatile String password;
	private Tomcat tomcat;

	/** A plain server (KEY STORE null) or an HTTPS one, at a context path ("" for the root). */
	WebDavTestServer(Path keyStore, String keyStorePassword, String contextPath) throws IOException {
		this.keyStore = keyStore;
		this.keyStorePassword = keyStorePassword;
		this.contextPath = contextPath == null ? "" : contextPath;
		home = Paths.get(FileSourceTestSupport.scratchDir("webdav-" + COUNT.incrementAndGet()));
		if( Files.exists(home) ) {
			// a folder left by an earlier run
			try (java.util.stream.Stream<Path> s = Files.walk(home)) {
				s.sorted(java.util.Comparator.reverseOrder()).filter(p -> !p.equals(home)).forEach(p -> p.toFile().delete());
			}
		}
		Files.createDirectories(home);
		TOMCAT_LOG.setLevel(Level.WARNING);
	}

	static WebDavTestServer started() throws IOException {
		return started(null, null, "");
	}

	static WebDavTestServer started(Path keyStore, String keyStorePassword, String contextPath) throws IOException {
		WebDavTestServer s = new WebDavTestServer(keyStore, keyStorePassword, contextPath);
		FileSourceTestSupport.startServer(s, 10000);
		return s;
	}

	/**
	 * Starts a server, connects a factory to it, and sets them as the factory and server under test
	 * (FileSourceTestSupport disconnects and stops them after the class).
	 */
	static WebDavTestServer startAndConnect() throws IOException {
		WebDavTestServer s = started();
		FileSourceTestSupport.server = s;
		FileSourceTestSupport.factory = s.connect();
		return s;
	}

	void login(String user, String password) {
		this.user = user;
		this.password = password;
	}

	@Override
	public synchronized void start() throws IOException {
		// Tomcat would install a URLStreamHandlerFactory for the JVM, which fails once parley-files has
		// installed its own: it isn't needed here
		org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
		Tomcat t = new Tomcat();
		Path base = Paths.get(FileSourceTestSupport.scratchDir("webdav-tomcat-" + COUNT.incrementAndGet()));
		Files.createDirectories(base);
		t.setBaseDir(base.toString());
		Connector c = new Connector();
		c.setPort(0);
		c.setProperty("address", HOST);
		if( keyStore != null ) {
			c.setSecure(true);
			c.setScheme("https");
			c.setProperty("SSLEnabled", "true");
			SSLHostConfig ssl = new SSLHostConfig();
			SSLHostConfigCertificate cert = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.RSA);
			cert.setCertificateKeystoreFile(keyStore.toString());
			cert.setCertificateKeystorePassword(keyStorePassword);
			cert.setCertificateKeystoreType("PKCS12");
			ssl.addCertificate(cert);
			c.addSslHostConfig(ssl);
		}
		t.getService().addConnector(c);
		t.setConnector(c);
		Context ctx = t.addContext(contextPath, home.toString());
		Wrapper w = Tomcat.addServlet(ctx, "webdav", new WebdavServlet());
		w.addInitParameter("readonly", "false");
		w.addInitParameter("listings", "true");
		ctx.addServletMappingDecoded("/*", "webdav");
		FilterDef fd = new FilterDef();
		fd.setFilterName("test");
		fd.setFilter(new TestFilter());
		ctx.addFilterDef(fd);
		FilterMap fm = new FilterMap();
		fm.setFilterName("test");
		fm.addURLPattern("/*");
		ctx.addFilterMap(fm);
		try {
			t.start();
		} catch (LifecycleException e) {
			throw new IOException("Can't start Tomcat", e);
		}
		// the tests change files on the disk behind Tomcat's back
		ctx.getResources().setCachingAllowed(false);
		tomcat = t;
	}

	@Override
	public synchronized void stop() throws IOException {
		Tomcat t = tomcat;
		tomcat = null;
		if( t != null ) {
			try {
				t.stop();
				t.destroy();
			} catch (LifecycleException e) {
				throw new IOException("Can't stop Tomcat", e);
			}
		}
	}

	@Override
	public synchronized boolean isRunning() {
		return tomcat != null;
	}

	@Override
	public String getName() {
		return keyStore == null ? "Tomcat WebDAV test server" : "Tomcat WebDAV test server (HTTPS)";
	}

	synchronized int port() {
		return tomcat.getConnector().getLocalPort();
	}

	/** The folder the server serves. */
	Path home() {
		return home;
	}

	Properties properties() {
		Properties p = new Properties();
		p.setProperty(WebDavFileSourceFactory.PROP_HOST, HOST);
		p.setProperty(WebDavFileSourceFactory.PROP_PORT, "" + port());
		p.setProperty(WebDavFileSourceFactory.PROP_TIMEOUT, "10s");
		if( !contextPath.isEmpty() ) {
			p.setProperty(WebDavFileSourceFactory.PROP_ROOT, contextPath);
		}
		if( user != null ) {
			p.setProperty(WebDavFileSourceFactory.PROP_USER, user);
			p.setProperty(WebDavFileSourceFactory.PROP_PSWD, password);
		}
		return p;
	}

	/** A new factory of the server's type, with its properties, not yet connected. */
	WebDavFileSourceFactory factory() {
		WebDavFileSourceFactory f = keyStore == null ? new WebDavFileSourceFactory() : new WebDavsFileSourceFactory();
		f.getLogger().setLevel(LogLevel.ERROR);
		f.setConnectionProperties(properties());
		return f;
	}

	/** A connected factory. */
	WebDavFileSourceFactory connect() throws IOException {
		WebDavFileSourceFactory f = factory();
		if( !f.connect() ) {
			throw new IOException("can't connect to " + getName());
		}
		return f;
	}

	/** The PROPFIND answer for "/phantom": itself, one real member, and three that aren't its own or aren't there. */
	static String phantomAnswer(String ctx, int depth) {
		String folder = "<D:response><D:href>" + ctx + "/phantom/</D:href><D:propstat><D:prop><D:resourcetype>\n  <D:collection/>\n"
				+ "</D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n";
		if( depth == 0 ) {
			return "<?xml version=\"1.0\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n" + folder + "</D:multistatus>\n";
		}
		return "<?xml version=\"1.0\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n" + folder
				+ "<D:response><D:href>" + ctx + "/phantom/real.txt</D:href><D:propstat><D:prop><D:resourcetype/>"
				+ "<D:getcontentlength>4</D:getcontentlength></D:prop>"
				+ "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n"
				+ "<D:response><D:href>" + ctx + "/phantom/ghost.txt</D:href><D:propstat><D:prop><D:resourcetype/></D:prop>"
				+ "<D:status>HTTP/1.1 404 Not Found</D:status></D:propstat></D:response>\n"
				+ "<D:response><D:href>" + ctx + "/phantom/gone.txt</D:href><D:status>HTTP/1.1 404 Not Found</D:status></D:response>\n"
				+ "<D:response><D:href>" + ctx + "/phantom/sub/deep.txt</D:href><D:propstat><D:prop><D:resourcetype/></D:prop>"
				+ "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n"
				+ "<D:response><D:href>/elsewhere/x.txt</D:href><D:propstat><D:prop><D:resourcetype/></D:prop>"
				+ "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n" + "</D:multistatus>\n";
	}

	private final class TestFilter implements Filter {

		@Override
		public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
			HttpServletRequest r = (HttpServletRequest) req;
			HttpServletResponse s = (HttpServletResponse) res;
			String u = user;
			if( u != null ) {
				String want = "Basic " + Base64.getEncoder().encodeToString((u + ":" + password).getBytes(StandardCharsets.UTF_8));
				if( !want.equals(r.getHeader("Authorization")) ) {
					s.setHeader("WWW-Authenticate", "Basic realm=\"test\"");
					s.sendError(401);
					return;
				}
			}
			// the path in the context, decoded
			String path = r.getServletPath() + (r.getPathInfo() == null ? "" : r.getPathInfo());
			if( "PUT".equals(r.getMethod()) && path.startsWith("/short/") ) {
				// keeps only the first half of what was sent, and says it was stored
				byte[] body;
				try (InputStream in = r.getInputStream()) {
					body = in.readAllBytes();
				}
				Path p = home.resolve(path.substring(1));
				Files.createDirectories(p.getParent());
				Files.write(p, java.util.Arrays.copyOf(body, body.length / 2));
				s.setStatus(201);
				return;
			}
			if( "PUT".equals(r.getMethod()) && path.startsWith("/full/") ) {
				try (InputStream in = r.getInputStream()) {
					in.transferTo(OutputStream.nullOutputStream());
				}
				s.sendError(507);
				return;
			}
			if( "PROPFIND".equals(r.getMethod()) && (path.equals("/phantom") || path.equals("/phantom/")) ) {
				String depth = r.getHeader("Depth");
				byte[] body = phantomAnswer(contextPath, "0".equals(depth) ? 0 : 1).getBytes(StandardCharsets.UTF_8);
				s.setStatus(207);
				s.setContentType("text/xml; charset=utf-8");
				s.setContentLength(body.length);
				try (OutputStream out = s.getOutputStream()) {
					out.write(body);
				}
				return;
			}
			chain.doFilter(req, res);
		}
	}
}
