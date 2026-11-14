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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;

/**
 * What a FileSource remembers: a change made through the factory is seen at once; one made by
 * someone else (here, on the server's disk) once the attribute cache time has passed, or at once
 * after a refresh.
 */
public class WebDavExternalChangeTest {

	private static WebDavTestServer server;
	private static WebDavFileSourceFactory factory;

	@BeforeAll
	static void start() throws IOException {
		server = WebDavTestServer.started();
		factory = server.connect();
	}

	@AfterAll
	static void stop() throws IOException {
		if( factory != null ) {
			java.util.Set<String> open = factory.openUploads();
			factory.disConnect();
			org.junit.jupiter.api.Assertions.assertTrue(open.isEmpty(), "uploads left open: " + open);
		}
		if( server != null ) {
			server.stop();
		}
	}

	@AfterEach
	void defaultTtl() {
		factory.setAttributeCacheTtl(WebDavFileSourceFactory.DEFAULT_ATTRIBUTE_CACHE_TTL);
	}

	@Test
	void anotherClientsChangesAreSeenAfterTheCacheTime() throws Exception {
		factory.setAttributeCacheTtl(300);
		FileSource dir = factory.createFileSource("/ext");
		assertTrue(dir.mkdir());
		assertEquals(0, dir.listFiles().length);
		Files.write(server.home().resolve("ext/theirs.txt"), new byte[] { 1, 2, 3 });
		FileSource theirs = dir.getChild("theirs.txt");
		Thread.sleep(400);
		assertEquals(1, dir.listFiles().length);
		assertEquals(3, theirs.length());
		Files.write(server.home().resolve("ext/theirs.txt"), new byte[] { 1 });
		Thread.sleep(400);
		assertEquals(1, theirs.length());
		Files.delete(server.home().resolve("ext/theirs.txt"));
		theirs.refresh();
		assertFalse(theirs.exists());
		assertTrue(dir.delete());
	}

	@Test
	void ownChangesAreSeenAtOnce() throws Exception {
		factory.setAttributeCacheTtl(60_000);
		FileSource dir = factory.createFileSource("/own");
		assertTrue(dir.mkdir());
		assertEquals(0, dir.listFiles().length);
		FileSource other = factory.createFileSource("/own/mine.txt");
		try (java.io.OutputStream out = other.getOutputStream()) {
			out.write(7);
		}
		assertEquals(1, dir.listFiles().length, "another FileSource's write is seen by the folder");
		assertEquals(1, factory.createFileSource("/own/mine.txt").length());
		assertTrue(other.delete());
		assertEquals(0, dir.listFiles().length);
		assertTrue(dir.delete());
	}
}
