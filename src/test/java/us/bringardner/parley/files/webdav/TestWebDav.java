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

import us.bringardner.parley.files.test.AbstractTestClass;
import us.bringardner.parley.files.test.FileSourceTestSupport;

/** The shared FileSource contract, against Tomcat's WebdavServlet. */
public class TestWebDav extends AbstractTestClass {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		localTestFileDirPath = FileSourceTestSupport.testFilesDir(TestWebDav.class);
		localCacheDirPath = FileSourceTestSupport.scratchDir("CacheFiles");
		remoteTestFileDirPath = "/TestFiles";
		WebDavTestServer.startAndConnect();
	}

	/** WebDAV has no permission bits to toggle: none can be set (WebDavPermissionsTest says what is reported). */
	@Override
	@org.junit.jupiter.api.Test
	@org.junit.jupiter.api.Order(3)
	public void testPermissions() throws IOException {
		org.junit.jupiter.api.Assumptions.abort("WebDAV has no permission bits");
	}
}
