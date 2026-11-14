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

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.lock.LockScope;
import us.bringardner.parley.files.test.AbstractLockTests;

/** The shared locking suite over WebDAV: locks reach this JVM only (WebDAV's LOCK isn't used). */
public class WebDavLockTest extends AbstractLockTests {

	private static final AtomicInteger DIRS = new AtomicInteger();
	private static WebDavTestServer server;
	private static WebDavFileSourceFactory factory;

	@BeforeAll
	static void startServer() throws Exception {
		server = WebDavTestServer.started();
		factory = server.connect();
	}

	@AfterAll
	static void stopServer() throws Exception {
		if( factory != null ) {
			java.util.Set<String> open = factory.openUploads();
			factory.disConnect();
			org.junit.jupiter.api.Assertions.assertTrue(open.isEmpty(), "uploads left open: " + open);
		}
		if( server != null ) {
			server.stop();
		}
	}

	@Override
	protected FileSource newLockDirectory() throws Exception {
		String name = "locks" + System.nanoTime() + "-" + DIRS.incrementAndGet();
		Files.createDirectories(server.home().resolve(name));
		return factory.createFileSource("/" + name);
	}

	@Test
	public void webdavLocksStayInThisJvm() {
		assertEquals(LockScope.PROCESS, dir.getLockScope());
	}
}
