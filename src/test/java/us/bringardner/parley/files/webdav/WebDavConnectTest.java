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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.logging.testing.ErrorsExpected;

/**
 * Connecting: a Basic login, a refused one, the root setting (a server whose WebDAV folder isn't at
 * "/"), and settings that point at nothing.
 */
@ErrorsExpected
public class WebDavConnectTest {

	private static WebDavTestServer server;
	private static WebDavTestServer rooted;
	private final List<FileSourceFactory> made = new ArrayList<>();

	@BeforeAll
	static void start() throws IOException {
		server = WebDavTestServer.started();
		server.login("tony", "s3cret:with colon");
		Files.write(server.home().resolve("hello.txt"), "hello".getBytes(StandardCharsets.UTF_8));
		rooted = WebDavTestServer.started(null, null, "/dav/files");
		Files.write(rooted.home().resolve("rooted.txt"), "rooted".getBytes(StandardCharsets.UTF_8));
	}

	@AfterAll
	static void stop() throws IOException {
		if( server != null ) {
			server.stop();
		}
		if( rooted != null ) {
			rooted.stop();
		}
	}

	@AfterEach
	void disconnectAll() throws IOException {
		for (FileSourceFactory f : made) {
			f.disConnect();
		}
		made.clear();
	}

	private WebDavFileSourceFactory factory(WebDavTestServer s, Properties change) {
		WebDavFileSourceFactory f = new WebDavFileSourceFactory();
		Properties p = s.properties();
		p.putAll(change);
		f.setConnectionProperties(p);
		made.add(f);
		return f;
	}

	private static Properties props(String... kv) {
		Properties p = new Properties();
		for (int i = 0; i < kv.length; i += 2) {
			p.setProperty(kv[i], kv[i + 1]);
		}
		return p;
	}

	@Test
	void aBasicLogin() throws IOException {
		WebDavFileSourceFactory f = factory(server, props());
		assertTrue(f.connect());
		assertTrue(f.isConnected());
		try (InputStream in = f.createFileSource("/hello.txt").getInputStream()) {
			assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		assertEquals("tony", f.whoAmI().getName());
	}

	@Test
	void aWrongPasswordIsRefusedAtConnect() {
		WebDavFileSourceFactory f = factory(server, props(WebDavFileSourceFactory.PROP_PSWD, "wrong"));
		WebDavException e = assertThrows(WebDavException.class, f::connect);
		assertEquals(401, e.getStatus());
		assertTrue(e.getMessage().contains("tony"), e.getMessage());
		assertFalse(e.getMessage().contains("wrong"), "the password isn't in the message");
		assertFalse(f.isConnected());
	}

	@Test
	void noLoginIsRefusedAtConnect() {
		Properties p = server.properties();
		p.remove(WebDavFileSourceFactory.PROP_USER);
		p.remove(WebDavFileSourceFactory.PROP_PSWD);
		WebDavFileSourceFactory f = new WebDavFileSourceFactory();
		f.setConnectionProperties(p);
		made.add(f);
		WebDavException e = assertThrows(WebDavException.class, f::connect);
		assertEquals(401, e.getStatus());
	}

	@Test
	void theRootIsTheServersFolder() throws IOException {
		WebDavFileSourceFactory f = factory(rooted, props());
		assertTrue(f.connect());
		FileSource root = f.listRoots()[0];
		assertEquals("/", root.getAbsolutePath());
		String[] names = root.list();
		assertEquals(1, names.length);
		assertEquals("rooted.txt", names[0]);
		FileSource made = f.createFileSource("/sub");
		assertTrue(made.mkdir());
		assertTrue(Files.isDirectory(rooted.home().resolve("sub")));
		assertTrue(made.delete());
		assertTrue(f.getTitle().endsWith("/dav/files"), f.getTitle());
	}

	@Test
	void aRootWithNothingThereIsRefusedAtConnect() {
		WebDavFileSourceFactory f = factory(rooted, props(WebDavFileSourceFactory.PROP_ROOT, "/dav/nothing"));
		assertThrows(IOException.class, f::connect);
		assertFalse(f.isConnected());
	}

	@Test
	void aRootThatIsAFileIsRefusedAtConnect() {
		WebDavFileSourceFactory f = factory(rooted, props(WebDavFileSourceFactory.PROP_ROOT, "/dav/files/rooted.txt"));
		IOException e = assertThrows(IOException.class, f::connect);
		assertTrue(e.getMessage().contains("not a WebDAV folder"), e.getMessage());
	}

	@Test
	void aServerThatIsntThereIsAnIOException() throws IOException {
		int port;
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			port = s.getLocalPort();
		}
		WebDavFileSourceFactory f = factory(server, props(WebDavFileSourceFactory.PROP_PORT, "" + port));
		assertThrows(IOException.class, f::connect);
		assertFalse(f.isConnected());
	}

	@Test
	void aServerThatDoesntAnswerTimesOut() throws IOException {
		try (java.net.ServerSocket silent = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName(WebDavTestServer.HOST))) {
			WebDavFileSourceFactory f = factory(server, props(WebDavFileSourceFactory.PROP_PORT, "" + silent.getLocalPort(),
					WebDavFileSourceFactory.PROP_TIMEOUT, "1s"));
			long start = System.currentTimeMillis();
			java.net.SocketTimeoutException e = assertThrows(java.net.SocketTimeoutException.class, f::connect);
			assertTrue(System.currentTimeMillis() - start < 8000, "took " + (System.currentTimeMillis() - start) + " ms");
			assertTrue(e.getMessage().contains("1 s"), e.getMessage());
		}
	}

	@Test
	void aMissingFileIsFileNotFound() throws IOException {
		WebDavFileSourceFactory f = factory(server, props());
		assertTrue(f.connect());
		FileSource missing = f.createFileSource("/missing.txt");
		assertFalse(missing.exists());
		assertThrows(FileNotFoundException.class, missing::getInputStream);
		assertEquals(null, missing.listFiles());
	}

	@Test
	void aUrlNamesTheSameFile() throws IOException {
		WebDavFileSourceFactory f = factory(rooted, props());
		assertTrue(f.connect());
		FileSource file = f.createFileSource("/rooted.txt");
		java.net.URL url = file.toURL();
		assertTrue(url.toString().contains("type=webdav"), url.toString());
		assertTrue(url.toString().contains("root=/dav/files"), url.toString());
		WebDavFileSourceFactory g = new WebDavFileSourceFactory();
		made.add(g);
		g.setConnectionProperties(url);
		assertEquals(f.getHost(), g.getHost());
		assertEquals(f.getPort(), g.getPort());
		assertEquals("/dav/files", g.getRoot());
		assertTrue(f.isSameFileSystem(g));
	}
}
