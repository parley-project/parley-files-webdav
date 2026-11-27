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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.SecureBaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * webdavs and its certificate check, on Tomcat over HTTPS with a self-signed certificate for
 * "localhost": trusted through the application's trust managers it works (read and write); one the
 * JVM doesn't trust is refused, and so is a trusted one for another host (the server's address).
 */
public class WebDavsTest {

	private static final String PASSWORD = "test-only";

	private static Path dir;
	private static TrustManager[] savedTrust;
	private static TrustManager[] trustTheTestCertificate;
	private static WebDavTestServer server;

	private final List<FileSourceFactory> made = new ArrayList<>();

	@BeforeAll
	static void start() throws Exception {
		dir = Files.createTempDirectory("parley-files-webdavs");
		Path ks = keyStore(dir, "CN=localhost", "dns:localhost");
		KeyStore trust = KeyStore.getInstance("PKCS12");
		trust.load(null, null);
		trust.setCertificateEntry("server", certificate(ks));
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(trust);
		trustTheTestCertificate = tmf.getTrustManagers();
		savedTrust = SecureBaseObject.getDefaultTrustManagers();
		server = WebDavTestServer.started(ks, PASSWORD, "");
		Files.write(server.home().resolve("a.txt"), "secure".getBytes(StandardCharsets.UTF_8));
	}

	@AfterAll
	static void stop() throws IOException {
		SecureBaseObject.setDefaultTrustManagers(savedTrust);
		try {
			if( server != null ) {
				server.stop();
			}
		} finally {
			if( dir != null ) {
				try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
					s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
				}
			}
		}
	}

	@AfterEach
	void disconnectAll() throws IOException {
		for (FileSourceFactory f : made) {
			f.disConnect();
		}
		made.clear();
		SecureBaseObject.setDefaultTrustManagers(savedTrust);
	}

	/** A PKCS12 key store with a new self-signed RSA key and certificate for DN and the SAN (keytool's -ext syntax). */
	static Path keyStore(Path dir, String dn, String san) throws IOException, InterruptedException {
		Path ks = dir.resolve("server.p12");
		String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
		Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
				"-dname", dn, "-ext", "SAN=" + san, "-validity", "2", "-keystore", ks.toString(), "-storetype", "PKCS12",
				"-storepass", PASSWORD, "-keypass", PASSWORD).redirectErrorStream(true).start();
		String out;
		try (InputStream in = p.getInputStream()) {
			out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		if( !p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0 ) {
			p.destroyForcibly();
			throw new IOException("keytool failed: " + out);
		}
		return ks;
	}

	static Certificate certificate(Path ks) throws Exception {
		KeyStore k = KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(ks)) {
			k.load(in, PASSWORD.toCharArray());
		}
		return k.getCertificate("server");
	}

	private WebDavsFileSourceFactory factory(String host) {
		WebDavsFileSourceFactory f = new WebDavsFileSourceFactory();
		Properties p = server.properties();
		p.setProperty(WebDavFileSourceFactory.PROP_HOST, host);
		f.setConnectionProperties(p);
		made.add(f);
		return f;
	}

	@Test
	void aTrustedCertificateForTheHost() throws IOException {
		SecureBaseObject.setDefaultTrustManagers(trustTheTestCertificate);
		WebDavsFileSourceFactory f = factory("localhost");
		assertTrue(f.connect());
		try (InputStream in = f.createFileSource("/a.txt").getInputStream()) {
			assertEquals("secure", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		FileSource w = f.createFileSource("/written.txt");
		try (OutputStream out = w.getOutputStream()) {
			out.write("over TLS".getBytes(StandardCharsets.UTF_8));
		}
		assertEquals("over TLS", new String(Files.readAllBytes(server.home().resolve("written.txt")), StandardCharsets.UTF_8));
		assertTrue(w.delete());
	}

	@Test
	void aCertificateTheJvmDoesntTrustIsRefused() {
		WebDavsFileSourceFactory f = factory("localhost");
		assertThrows(IOException.class, f::connect);
		assertFalse(f.isConnected());
	}

	@Test
	void aTrustedCertificateForAnotherHostIsRefused() {
		SecureBaseObject.setDefaultTrustManagers(trustTheTestCertificate);
		WebDavsFileSourceFactory f = factory(WebDavTestServer.HOST);
		assertThrows(IOException.class, f::connect, "the certificate is for localhost only");
		assertFalse(f.isConnected());
	}

	@Test
	void webdavsIsHttpsOn443UnlessSet() {
		WebDavsFileSourceFactory f = new WebDavsFileSourceFactory();
		assertEquals("webdavs", f.getTypeId());
		assertEquals(443, f.getPort());
		assertTrue(f.isSecure());
		assertTrue(f.createThreadSafeCopy() instanceof WebDavsFileSourceFactory);
		assertEquals(80, new WebDavFileSourceFactory().getPort());
	}
}
