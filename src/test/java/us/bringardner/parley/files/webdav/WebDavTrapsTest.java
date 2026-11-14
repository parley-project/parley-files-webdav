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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;

/**
 * What went wrong with VFS's WebDAV (To-Do 17.1) and what WebDAV servers commonly do, on Tomcat
 * (which writes its PROPFIND answers over several lines): an upload the server refuses or cuts short
 * is reported, a folder isn't its own child and can be deleted, members that aren't there or aren't
 * the folder's own are left out, and names with spaces, "%", "+", "#" and non-ASCII letters work.
 */
public class WebDavTrapsTest {

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

	private static byte[] bytes(int n, long seed) {
		byte[] ret = new byte[n];
		new Random(seed).nextBytes(ret);
		return ret;
	}

	@Test
	void anUploadTheServerRefusesIsAnError() throws IOException {
		Files.createDirectories(server.home().resolve("full"));
		FileSource f = factory.createFileSource("/full/refused.bin");
		OutputStream out = f.getOutputStream();
		out.write(bytes(1000, 1));
		WebDavException e = assertThrows(WebDavException.class, out::close);
		assertEquals(507, e.getStatus(), e.getMessage());
		assertTrue(e.getMessage().contains("Insufficient Storage"), e.getMessage());
	}

	@Test
	void anUploadTheServerCutsShortIsAnError() throws IOException {
		Files.createDirectories(server.home().resolve("short"));
		FileSource f = factory.createFileSource("/short/cut.bin");
		OutputStream out = f.getOutputStream();
		out.write(bytes(1000, 2));
		IOException e = assertThrows(IOException.class, out::close);
		assertTrue(e.getMessage().contains("stored 500 bytes"), e.getMessage());
	}

	@Test
	void anUploadIntoAMissingFolderFailsAtOnce() throws IOException {
		FileSource f = factory.createFileSource("/no/such/folder/x.txt");
		assertThrows(FileNotFoundException.class, f::getOutputStream);
	}

	@Test
	void anUploadIntoAFolderTheServerDoesntHaveIsAnError() throws IOException {
		// the folder is there when the stream is opened, and gone when the server stores it
		FileSource dir = factory.createFileSource("/vanishing");
		assertTrue(dir.mkdir());
		FileSource f = dir.getChild("x.txt");
		OutputStream out = f.getOutputStream();
		Files.delete(server.home().resolve("vanishing"));
		out.write("data".getBytes(StandardCharsets.UTF_8));
		IOException e = assertThrows(IOException.class, out::close);
		assertTrue(e.getMessage().contains("409"), e.getMessage());
	}

	@Test
	void aLargeFileIsStreamedBothWays() throws IOException {
		FileSource f = factory.createFileSource("/large.bin");
		byte[] block = bytes(64 * 1024, 3);
		int blocks = 160; // 10 MB
		try (OutputStream out = f.getOutputStream()) {
			for (int i = 0; i < blocks; i++) {
				block[0] = (byte) i;
				out.write(block);
			}
		}
		assertEquals((long) block.length * blocks, f.length());
		assertEquals((long) block.length * blocks, Files.size(server.home().resolve("large.bin")));
		try (InputStream in = f.getInputStream()) {
			byte[] got = new byte[block.length];
			for (int i = 0; i < blocks; i++) {
				assertEquals(block.length, in.readNBytes(got, 0, got.length));
				block[0] = (byte) i;
				assertArrayEquals(block, got, "block " + i);
			}
			assertEquals(-1, in.read());
		}
		assertTrue(f.delete());
	}

	@Test
	void aFolderIsntItsOwnChildAndCanBeDeleted() throws IOException {
		FileSource dir = factory.createFileSource("/selfless");
		assertTrue(dir.mkdir());
		FileSource kid = dir.getChild("kid.txt");
		try (OutputStream out = kid.getOutputStream()) {
			out.write(1);
		}
		FileSource[] kids = dir.listFiles();
		assertEquals(1, kids.length, Arrays.toString(kids));
		assertEquals("kid.txt", kids[0].getName());
		assertFalse(dir.delete(), "a folder that isn't empty isn't deleted (WebDAV's DELETE would take it all)");
		assertTrue(Files.exists(server.home().resolve("selfless/kid.txt")));
		assertTrue(kid.delete());
		assertEquals(0, dir.listFiles().length);
		assertTrue(dir.delete());
		assertFalse(dir.exists());
		assertFalse(Files.exists(server.home().resolve("selfless")));
	}

	@Test
	void membersThatArentThereOrArentItsOwnAreLeftOut() throws IOException {
		FileSource dir = factory.createFileSource("/phantom");
		assertTrue(dir.isDirectory());
		FileSource[] kids = dir.listFiles();
		assertEquals(1, kids.length, Arrays.toString(kids));
		assertEquals("real.txt", kids[0].getName());
		assertEquals(4, kids[0].length());
		assertTrue(kids[0].isFile());
	}

	@Test
	void oddNamesWork() throws IOException {
		FileSource dir = factory.createFileSource("/odd names");
		assertTrue(dir.mkdir());
		java.util.List<String> all = new java.util.ArrayList<>(Arrays.asList("a b.txt", "100%.txt", "1+1=2.txt", "hash#tag.txt", "q?.txt",
				"semi;colon.txt", "it's & (so).txt", "[brackets] {braces}.txt", "~tilde`.txt"));
		if( "UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding")) ) {
			// the server's disk takes names in the JVM's file name encoding: ASCII on a Linux with no locale set
			all.add("caf\u00e9 \u65e5\u672c.txt");
		}
		String[] names = all.toArray(new String[0]);
		for (String n : names) {
			FileSource f = dir.getChild(n);
			try (OutputStream out = f.getOutputStream()) {
				out.write(n.getBytes(StandardCharsets.UTF_8));
			}
			assertTrue(Files.exists(server.home().resolve("odd names").resolve(n)), n + " on the server's disk");
			try (InputStream in = factory.createFileSource("/odd names/" + n).getInputStream()) {
				assertEquals(n, new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
		factory.createFileSource("/").refresh();
		TreeSet<String> listed = new TreeSet<>(Arrays.asList(factory.createFileSource("/odd names").list()));
		assertEquals(new TreeSet<>(Arrays.asList(names)), listed);
		FileSource renamed = dir.getChild("renamed " + (all.size() > 9 ? "\u00e9" : "e") + ".txt");
		assertTrue(dir.getChild(names[0]).renameTo(renamed));
		assertTrue(renamed.exists());
		for (String n : factory.createFileSource("/odd names").list()) {
			assertTrue(dir.getChild(n).delete(), n);
		}
		assertTrue(dir.delete());
	}

	@Test
	void createNewFileDoesntReplaceAFileThatAppeared() throws IOException {
		FileSource f = factory.createFileSource("/created.txt");
		assertTrue(f.createNewFile());
		assertTrue(factory.getClient().misreadsIfNoneMatch(), "Tomcat 10.1 answers If-None-Match: * with 412 when nothing is there");
		assertFalse(f.createNewFile());
		assertEquals(0, f.length());
		// another client wrote it meanwhile: the PUT with If-None-Match: * must not empty it
		Files.write(server.home().resolve("created.txt"), "theirs".getBytes(StandardCharsets.UTF_8));
		f.refresh();
		assertFalse(f.createNewFile());
		assertEquals("theirs", new String(Files.readAllBytes(server.home().resolve("created.txt")), StandardCharsets.UTF_8));
		assertTrue(f.delete());
	}

	@Test
	void renameDoesntReplaceAndCanBeToldTo() throws IOException {
		FileSource a = factory.createFileSource("/ren-a.txt");
		FileSource b = factory.createFileSource("/ren-b.txt");
		try (OutputStream out = a.getOutputStream()) {
			out.write('a');
		}
		try (OutputStream out = b.getOutputStream()) {
			out.write('b');
		}
		assertFalse(a.renameTo(b), "Overwrite: F");
		assertTrue(a.renameTo(b, true));
		assertFalse(a.exists());
		try (InputStream in = b.getInputStream()) {
			assertEquals('a', in.read());
		}
		assertTrue(b.delete());
	}

	@Test
	void aFolderMovesWithWhatIsInIt() throws IOException {
		FileSource from = factory.createFileSource("/move-from");
		assertTrue(from.getChild("sub").mkdirs());
		try (OutputStream out = from.getChild("sub/x.txt").getOutputStream()) {
			out.write('x');
		}
		FileSource to = factory.createFileSource("/move-to");
		assertTrue(from.renameTo(to));
		assertFalse(from.exists());
		assertTrue(to.getChild("sub/x.txt").isFile());
		assertFalse(to.renameTo(to.getChild("sub/inside")), "a folder into itself");
		assertTrue(to.getChild("sub/x.txt").delete());
		assertTrue(to.getChild("sub").delete());
		assertTrue(to.delete());
	}

	@Test
	void whatTheServerSaysOfAFile() throws IOException {
		FileSource f = factory.createFileSource("/said.txt");
		long before = System.currentTimeMillis() / 1000 * 1000;
		try (OutputStream out = f.getOutputStream()) {
			out.write("12345".getBytes(StandardCharsets.UTF_8));
		}
		assertEquals(5, f.length());
		assertTrue(f.lastModified() >= before - 2000, "last modified " + f.lastModified() + " before " + before);
		assertTrue(f.isFile());
		assertFalse(f.isDirectory());
		assertTrue(f.canRead());
		assertTrue(f.canWrite());
		assertFalse(f.canExecute());
		assertFalse(f.setLastModified(1000));
		assertFalse(f.setExecutable(true));
		assertNull(f.getLinkedTo());
		assertTrue(f.delete());
		assertFalse(f.canRead());
	}

	@Test
	void appendSendsTheFileAgainWithTheNewBytes() throws IOException {
		FileSource f = factory.createFileSource("/append.txt");
		try (OutputStream out = f.getOutputStream(true)) {
			out.write("one".getBytes(StandardCharsets.UTF_8));
		}
		try (OutputStream out = f.getOutputStream(true)) {
			out.write(" two".getBytes(StandardCharsets.UTF_8));
		}
		assertEquals("one two", new String(Files.readAllBytes(server.home().resolve("append.txt")), StandardCharsets.UTF_8));
		assertTrue(f.delete());
	}

	@Test
	void theTempFilesAreGone() throws IOException {
		Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
		long before = count(tmp);
		FileSource f = factory.createFileSource("/tmpcheck.txt");
		try (OutputStream out = f.getOutputStream()) {
			out.write('1');
		}
		try (OutputStream out = f.getOutputStream(true)) {
			out.write('2');
		}
		assertEquals(before, count(tmp), "an append's local copy is deleted");
		assertTrue(f.delete());
	}

	/**
	 * Tomcat 10.1 holds a lock on a file while it is uploaded, so asking it about the file waits for
	 * the upload to end: the code writing it is answered by the factory instead, and doesn't wait on itself.
	 */
	@Test
	void aFileBeingWrittenCanBeAskedAbout() throws IOException {
		factory.setAttributeCacheTtl(0);
		try {
			FileSource f = factory.createFileSource("/writing.txt");
			long start = System.currentTimeMillis();
			try (OutputStream out = f.getOutputStream()) {
				out.write("12345".getBytes(StandardCharsets.UTF_8));
				assertTrue(f.exists());
				assertTrue(f.isFile());
				assertEquals(5, factory.createFileSource("/writing.txt").length(), "what has been written so far");
			}
			assertTrue(System.currentTimeMillis() - start < 5000, "no wait for a timeout");
			assertEquals(5, f.length());
			assertTrue(f.delete());
		} finally {
			factory.setAttributeCacheTtl(WebDavFileSourceFactory.DEFAULT_ATTRIBUTE_CACHE_TTL);
		}
	}

	/** A stream someone didn't close doesn't keep disconnect waiting, and isn't stored. */
	@Test
	void anUploadLeftOpenDoesntHoldUpDisconnect() throws IOException {
		WebDavFileSourceFactory f = server.connect();
		f.setLogLevel(us.bringardner.parley.core.LogLevel.NONE);
		OutputStream leaked = f.createFileSource("/leaked.txt").getOutputStream();
		leaked.write("never closed".getBytes(StandardCharsets.UTF_8));
		long start = System.currentTimeMillis();
		f.disConnect();
		assertTrue(System.currentTimeMillis() - start < 5000, "took " + (System.currentTimeMillis() - start) + " ms");
		assertThrows(IOException.class, leaked::close, "it wasn't stored");
		assertFalse(Files.exists(server.home().resolve("leaked.txt")) && Files.size(server.home().resolve("leaked.txt")) == 12,
				"the server didn't take what was sent as the whole file");
	}

	private static long count(Path dir) throws IOException {
		try (java.util.stream.Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().startsWith("parley-webdav-")).count();
		}
	}
}
