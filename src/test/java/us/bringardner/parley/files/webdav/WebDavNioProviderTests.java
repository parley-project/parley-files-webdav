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

import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.test.AbstractNioProviderTests;
import us.bringardner.parley.files.test.FileSourceTestSupport;

/** The NIO provider over WebDAV, against Tomcat's WebdavServlet. */
public class WebDavNioProviderTests extends AbstractNioProviderTests {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		localTestFileDirPath = FileSourceTestSupport.testFilesDir(WebDavNioProviderTests.class);
		localCacheDirPath = FileSourceTestSupport.scratchDir("NioCacheFiles");
		remoteTestFileDirPath = "/TestFiles";
		WebDavTestServer.startAndConnect();
	}

	/**
	 * A channel opened for writing only is a streamed PUT, which the server stores when the upload
	 * ends: force() can only send the data. (One opened for reading and writing works on a local copy
	 * that force() sends back.)
	 */
	@Override
	protected boolean forcedWritesAreVisibleBeforeClose() {
		return false;
	}

	@Override
	@org.junit.jupiter.api.Test
	public void testFileAttributes() throws Exception {
		org.junit.jupiter.api.Assumptions.abort("WebDAV has no permission bits to set");
	}

	@Override
	@org.junit.jupiter.api.Test
	public void testCreateDir() throws Exception {
		org.junit.jupiter.api.Assumptions.abort("WebDAV has no permission bits to set");
	}
}
