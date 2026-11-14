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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.ConnectionSettings;
import us.bringardner.parley.files.FileSourceFactory;

/** The settings a connection has, which are secret, how they are saved, and what a copy keeps. */
public class WebDavSettingsTest {

	@Test
	public void everyPropertyIsDescribedAndSecretsStaySecret() {
		for (WebDavFileSourceFactory f : new WebDavFileSourceFactory[] { new WebDavFileSourceFactory(), new WebDavsFileSourceFactory() }) {
			List<ConnectionSetting> settings = f.getConnectionSettings();
			for (String key : f.getConnectProperties().stringPropertyNames()) {
				ConnectionSetting s = ConnectionSettings.find(settings, key);
				assertNotNull(s, key + " isn't described");
				assertEquals(f.isSecretProperty(key), s.isSecret(), key);
			}
		}
	}

	@Test
	public void secretPropertiesAreExactlyTheseOnes() {
		WebDavFileSourceFactory factory = new WebDavFileSourceFactory();
		List<String> secrets = Arrays.asList(WebDavFileSourceFactory.PROP_PSWD);
		TreeSet<String> names = new TreeSet<>(factory.getConnectProperties().stringPropertyNames());
		names.addAll(secrets);
		for (String name : names) {
			assertEquals(secrets.contains(name), factory.isSecretProperty(name), name);
		}
	}

	@Test
	public void aNewConnectionStartsEmpty() {
		WebDavFileSourceFactory f = new WebDavFileSourceFactory();
		Properties p = ConnectionSettings.initialValues(f.getConnectionSettings(), f.getConnectProperties());
		assertEquals("", p.getProperty(WebDavFileSourceFactory.PROP_HOST));
		assertEquals("", p.getProperty(WebDavFileSourceFactory.PROP_USER));
		assertEquals("/", p.getProperty(WebDavFileSourceFactory.PROP_ROOT));
		assertEquals("" + WebDavFileSourceFactory.DEFAULT_PORT, p.getProperty(WebDavFileSourceFactory.PROP_PORT));
		assertEquals("30s", p.getProperty(WebDavFileSourceFactory.PROP_TIMEOUT));
	}

	@Test
	public void checkedBeforeConnecting() {
		WebDavFileSourceFactory f = new WebDavFileSourceFactory();
		Properties p = new Properties();
		p.setProperty(WebDavFileSourceFactory.PROP_PORT, "x");
		p.setProperty(WebDavFileSourceFactory.PROP_TIMEOUT, "-5");
		assertEquals(List.of("Host is required", "Port must be a whole number", "Timeout must be a duration (30s, 500ms, 5m)"),
				f.validateConnection(p));
	}

	@Test
	public void settingsSurviveARoundTrip() {
		WebDavFileSourceFactory f = new WebDavFileSourceFactory();
		Properties p = f.getConnectProperties();
		p.setProperty(WebDavFileSourceFactory.PROP_HOST, "example.org");
		p.setProperty(WebDavFileSourceFactory.PROP_TIMEOUT, "9000");
		p.setProperty(WebDavFileSourceFactory.PROP_ROOT, "remote.php/dav/files/tony/");
		p.setProperty(WebDavFileSourceFactory.PROP_USER, "tony");
		p.setProperty(WebDavFileSourceFactory.PROP_PSWD, "@keychain:webdav");
		f.setConnectionProperties(p);
		Properties back = f.getConnectProperties();
		assertEquals("9s", back.getProperty(WebDavFileSourceFactory.PROP_TIMEOUT), "saved as a duration");
		assertEquals("/remote.php/dav/files/tony", back.getProperty(WebDavFileSourceFactory.PROP_ROOT), "normalized");
		assertEquals("@keychain:webdav", back.getProperty(WebDavFileSourceFactory.PROP_PSWD), "a reference stays one");
		assertFalse(back.containsKey(WebDavFileSourceFactory.PROP_BUFFER_SIZE), "only saved when set");
		f.setBufferSize(1);
		assertEquals(WebDavFileSourceFactory.MIN_BUFFER_SIZE, f.getBufferSize());
		assertEquals("" + WebDavFileSourceFactory.MIN_BUFFER_SIZE, f.getConnectProperties().getProperty(WebDavFileSourceFactory.PROP_BUFFER_SIZE));
	}

	@Test
	public void aCopyHasTheSameConfigurationButIsIndependent() {
		WebDavsFileSourceFactory f = new WebDavsFileSourceFactory();
		f.setHost("dav.example.org");
		f.setPort(8443);
		f.setUser("tony");
		f.setPasswd("pw");
		f.setRoot("/dav");
		f.setTimeout(5000);
		f.setBufferSize(100_000);
		f.setAttributeCacheTtl(0);
		FileSourceFactory c = f.createThreadSafeCopy();
		assertNotSame(f, c);
		assertTrue(c instanceof WebDavsFileSourceFactory);
		assertEquals(f.getConnectProperties(), c.getConnectProperties());
		assertEquals(0, ((WebDavFileSourceFactory) c).getAttributeCacheTtl());
		assertTrue(f.isSameFileSystem(c));
		f.setRoot("/other");
		assertEquals("/dav", ((WebDavFileSourceFactory) c).getRoot());
		assertFalse(f.isSameFileSystem(c));
		WebDavFileSourceFactory plain = new WebDavFileSourceFactory();
		plain.setConnectionProperties(c.getConnectProperties());
		assertFalse(plain.isSameFileSystem(c), "HTTP and HTTPS aren't the same file system");
	}

	@Test
	public void bothTypesAreFoundAndHaveUrlHandlers() throws Exception {
		TreeSet<String> ids = new TreeSet<>();
		for (FileSourceFactory f : ServiceLoader.load(FileSourceFactory.class)) {
			ids.add(f.getTypeId());
		}
		assertTrue(ids.containsAll(List.of("webdav", "webdavs")), ids.toString());
		assertTrue(FileSourceFactory.getFileSourceFactory("webdav") instanceof WebDavFileSourceFactory);
		assertTrue(FileSourceFactory.getFileSourceFactory("webdavs") instanceof WebDavsFileSourceFactory);
		assertNotNull(Class.forName("us.bringardner.parley.files.webdav.Handler"));
		assertNotNull(Class.forName("us.bringardner.parley.files.webdavs.Handler"));
	}

	@Test
	public void pathsAreEncodedAndDecoded() {
		assertEquals("/a%20b/caf%C3%A9/100%25/1%2B1/x%3Fy%23z", WebDavClient.encodePath("/a b/café/100%/1+1/x?y#z"));
		assertEquals("/a b/café/100%/1+1", WebDavClient.decodePath("/a%20b/caf%C3%A9/100%25/1+1"));
		assertEquals("/raw space/é", WebDavClient.decodePath("/raw space/é"), "an href a server didn't encode");
		assertEquals("/bad%zz", WebDavClient.decodePath("/bad%zz"), "a % that isn't an escape stays");
	}

	@Test
	public void datesInEitherForm() {
		assertEquals(784111777000L, WebDavClient.parseDate("Sun, 06 Nov 1994 08:49:37 GMT"));
		assertEquals(784111777000L, WebDavClient.parseDate("1994-11-06T08:49:37Z"));
		assertEquals(784111777000L, WebDavClient.parseDate("1994-11-06T03:49:37-05:00"));
		assertEquals(0, WebDavClient.parseDate("not a date"));
	}
}
