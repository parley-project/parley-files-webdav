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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.files.StreamOptions;

/**
 * Random access ("r" in chunks with range requests; "rw" on a local copy sent back on sync and
 * close) and the seekable stream, against Tomcat, compared with a local RandomAccessFile.
 */
public class WebDavRandomAccessTest {

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

	private static FileSource file(String name, byte[] data) throws IOException {
		Files.write(server.home().resolve(name), data);
		FileSource f = factory.createFileSource("/" + name);
		f.refresh();
		return f;
	}

	@Test
	void readsChunksFromAnywhere() throws Exception {
		byte[] data = bytes(100_000, 1);
		FileSource f = file("ra-read.bin", data);
		try (IRandomAccessStream ra = f.getRandomAccessStream("r", StreamOptions.chunk(8192))) {
			assertEquals(data.length, ra.length());
			Random r = new Random(2);
			for (int i = 0; i < 50; i++) {
				int pos = r.nextInt(data.length);
				int len = Math.min(1 + r.nextInt(20_000), data.length - pos);
				byte[] got = new byte[len];
				ra.seek(pos);
				ra.readFully(got);
				assertArrayEquals(Arrays.copyOfRange(data, pos, pos + len), got, "at " + pos);
			}
			ra.seek(data.length);
			assertEquals(-1, ra.read());
			assertThrows(IOException.class, () -> ra.write(1), "read only");
		}
	}

	@Test
	void writesInTheMiddleAndAtTheStartKeepTheRest() throws Exception {
		byte[] data = bytes(50_000, 3);
		FileSource f = file("ra-write.bin", data);
		try (IRandomAccessStream ra = f.getRandomAccessStream("rw")) {
			ra.seek(0);
			ra.write(new byte[] { 1, 2, 3 });
			ra.seek(25_000);
			ra.write(new byte[] { 4, 5, 6 });
			ra.seek(data.length);
			ra.write(new byte[] { 7, 8 });
		}
		byte[] want = Arrays.copyOf(data, data.length + 2);
		System.arraycopy(new byte[] { 1, 2, 3 }, 0, want, 0, 3);
		System.arraycopy(new byte[] { 4, 5, 6 }, 0, want, 25_000, 3);
		want[data.length] = 7;
		want[data.length + 1] = 8;
		assertArrayEquals(want, Files.readAllBytes(server.home().resolve("ra-write.bin")));
		assertTrue(f.delete());
	}

	@Test
	void syncStoresBeforeClose() throws Exception {
		FileSource f = file("ra-sync.bin", new byte[] { 0, 0, 0, 0 });
		try (IRandomAccessStream ra = f.getRandomAccessStream("rws")) {
			ra.seek(2);
			ra.write(9);
			ra.sync();
			assertArrayEquals(new byte[] { 0, 0, 9, 0 }, Files.readAllBytes(server.home().resolve("ra-sync.bin")));
			ra.write(8);
		}
		assertArrayEquals(new byte[] { 0, 0, 9, 8 }, Files.readAllBytes(server.home().resolve("ra-sync.bin")));
		assertTrue(f.delete());
	}

	@Test
	void setLengthGrowsAndShrinks() throws Exception {
		FileSource f = file("ra-len.bin", bytes(1000, 4));
		try (IRandomAccessStream ra = f.getRandomAccessStream("rw")) {
			ra.setLength(5000);
			assertEquals(5000, ra.length());
		}
		assertEquals(5000, Files.size(server.home().resolve("ra-len.bin")));
		try (IRandomAccessStream ra = f.getRandomAccessStream("rw")) {
			ra.setLength(10);
		}
		assertEquals(10, Files.size(server.home().resolve("ra-len.bin")));
		assertTrue(f.delete());
	}

	@Test
	void actsLikeRandomAccessFile() throws Exception {
		Path local = Files.createTempFile("webdav-ra-compare", ".bin");
		try {
			byte[] data = bytes(20_000, 5);
			Files.write(local, data);
			FileSource f = file("ra-compare.bin", data);
			Random r = new Random(6);
			try (RandomAccessFile raf = new RandomAccessFile(local.toFile(), "rw"); IRandomAccessStream ra = f.getRandomAccessStream("rw")) {
				for (int i = 0; i < 300; i++) {
					long pos = r.nextInt(25_000);
					raf.seek(pos);
					ra.seek(pos);
					switch (r.nextInt(4)) {
					case 0:
						assertEquals(raf.read(), ra.read(), "read at " + pos);
						break;
					case 1: {
						byte[] b = bytes(1 + r.nextInt(500), i);
						raf.write(b);
						ra.write(b);
						break;
					}
					case 2: {
						byte[] a = new byte[1 + r.nextInt(500)];
						byte[] b = new byte[a.length];
						assertEquals(raf.read(a), ra.read(b), "count at " + pos);
						assertArrayEquals(a, b, "bytes at " + pos);
						break;
					}
					default:
						assertEquals(raf.length(), ra.length());
					}
				}
			}
			assertArrayEquals(Files.readAllBytes(local), Files.readAllBytes(server.home().resolve("ra-compare.bin")));
			assertTrue(f.delete());
		} finally {
			Files.deleteIfExists(local);
		}
	}

	@Test
	void modes() throws Exception {
		FileSource missing = factory.createFileSource("/ra-missing.bin");
		assertThrows(FileNotFoundException.class, () -> missing.getRandomAccessStream("r"));
		try (IRandomAccessStream ra = missing.getRandomAccessStream("rw")) {
			assertEquals(0, ra.length());
		}
		assertTrue(Files.exists(server.home().resolve("ra-missing.bin")), "rw creates it");
		assertTrue(missing.delete());
		FileSource dir = factory.createFileSource("/ra-dir");
		assertTrue(dir.mkdir());
		assertThrows(FileNotFoundException.class, () -> dir.getRandomAccessStream("r"));
		assertThrows(FileNotFoundException.class, () -> dir.getRandomAccessStream("rw"));
		assertThrows(IllegalArgumentException.class, () -> factory.createFileSource("/x").getRandomAccessStream("w"));
		assertTrue(dir.delete());
	}

	@Test
	void nothingWrittenIsNothingSent() throws Exception {
		FileSource f = file("ra-untouched.bin", new byte[] { 1, 2, 3 });
		long before = Files.getLastModifiedTime(server.home().resolve("ra-untouched.bin")).toMillis();
		Thread.sleep(1100);
		try (IRandomAccessStream ra = f.getRandomAccessStream("rw")) {
			assertEquals(2, ra.read(new byte[2]));
		}
		assertEquals(before, Files.getLastModifiedTime(server.home().resolve("ra-untouched.bin")).toMillis());
		assertTrue(f.delete());
	}

	@Test
	void theSeekableStreamReadsAnywhere() throws Exception {
		byte[] data = bytes(300_000, 7);
		FileSource f = file("seek.bin", data);
		ISeekableInputStream in = f.getSeekableInputStream();
		try {
			assertEquals(data.length, in.length());
			byte[] b = new byte[1000];
			assertEquals(1000, in.getInputStream().readNBytes(b, 0, 1000));
			assertArrayEquals(Arrays.copyOf(data, 1000), b);
			in.seek(200_000);
			assertEquals(1000, in.getInputStream().readNBytes(b, 0, 1000));
			assertArrayEquals(Arrays.copyOfRange(data, 200_000, 201_000), b);
			in.seek(10);
			assertEquals(data[10] & 0xff, in.read());
			in.seek(10 + 100_000);
			assertEquals(data[10 + 100_000] & 0xff, in.read());
			in.seek(data.length);
			assertEquals(-1, in.read());
			in.seek(data.length + 100);
			assertEquals(-1, in.read());
			assertEquals(data.length + 100, in.getFilePointer());
		} finally {
			// ISeekableInputStream isn't AutoCloseable: its stream is
			in.getInputStream().close();
		}
		assertThrows(FileNotFoundException.class, () -> factory.createFileSource("/nope.bin").getSeekableInputStream());
		assertTrue(f.delete());
	}

	@Test
	void anInputStreamFromAPlace() throws Exception {
		byte[] data = bytes(10_000, 8);
		FileSource f = file("from.bin", data);
		try (java.io.InputStream in = f.getInputStream(9_000)) {
			assertArrayEquals(Arrays.copyOfRange(data, 9_000, 10_000), in.readAllBytes());
		}
		try (java.io.InputStream in = f.getInputStream(10_000)) {
			assertEquals(-1, in.read(), "at the end");
		}
		try (java.io.InputStream in = f.getInputStream(20_000)) {
			assertEquals(-1, in.read(), "past the end");
		}
		assertTrue(f.delete());
	}
}
