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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.IRandomAccessIoController;

/**
 * Random access for writing (modes "rw", "rws" and "rwd") to a WebDAV file, on a local copy: WebDAV
 * has no way to write part of a file, and sending the whole file for every chunk written would be
 * far slower. The file is downloaded to a local temporary file when the stream is opened (created on
 * the server first if it is missing); reads and writes go to the copy; the copy is sent back whole
 * (a PUT, checked as every upload is) when {@link #sync()} is called and when the stream is closed,
 * if anything was written.
 * <p>
 * So "rws" and "rwd" store on every sync, not on every write; a change someone else makes to the
 * file while it is open is overwritten when it is sent back. Nothing is locked.
 */
public class WebDavLocalCopyIoController implements IRandomAccessIoController {

	private final WebDavFileSource dav;
	private final Path local;
	private final RandomAccessFile copy;
	private boolean changed;
	private boolean closed;

	/**
	 * @param mode "rw", "rws" or "rwd"
	 * @throws IllegalArgumentException for any other mode
	 * @throws FileNotFoundException if it's a directory or can't be created
	 */
	public WebDavLocalCopyIoController(WebDavFileSource file, String mode) throws IOException {
		if( !("rw".equals(mode) || "rws".equals(mode) || "rwd".equals(mode))) {
			throw new IllegalArgumentException("Illegal mode \""+mode+"\" must be one of \"r\", \"rw\", \"rws\", or \"rwd\"");
		}
		this.dav = file;
		String path = file.getAbsolutePath();
		file.refresh();
		if( file.isDirectory() ) {
			throw new FileNotFoundException(path+" (Is a directory)");
		}
		if( !file.exists() ) {
			try {
				file.createNewFile();
			} catch (IOException e) {
				FileNotFoundException fnf = new FileNotFoundException(path+" ("+e.getMessage()+")");
				fnf.initCause(e);
				throw fnf;
			}
			if( !file.exists() ) {
				throw new FileNotFoundException(path+" (Can't create it)");
			}
		}
		local = Files.createTempFile("parley-webdav-ra-", ".tmp");
		RandomAccessFile raf = null;
		try {
			if( file.length() > 0 ) {
				try (InputStream in = file.getInputStream()) {
					Files.copy(in, local, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			raf = new RandomAccessFile(local.toFile(), "rw");
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(local);
			throw e;
		}
		copy = raf;
	}

	/** true: the constructor checked the file on the server (made it if it was missing). */
	@Override
	public boolean checksAccessOnOpen() {
		return true;
	}

	private void checkOpen() throws IOException {
		if( closed ) {
			throw new IOException("Already closed");
		}
	}

	@Override
	public int read(long position) throws IOException {
		checkOpen();
		if( position < 0 ) {
			throw new IOException("Negative position");
		}
		copy.seek(position);
		return copy.read();
	}

	@Override
	public int read(long position, byte[] b, int off, int len) throws IOException {
		java.util.Objects.checkFromIndexSize(off, len, b.length);
		checkOpen();
		if( position < 0 ) {
			throw new IOException("Negative position");
		}
		if( len == 0 ) {
			return 0;
		}
		copy.seek(position);
		return copy.read(b, off, len);
	}

	@Override
	public void write(long position, byte value) throws IOException {
		checkOpen();
		if( position < 0 ) {
			throw new IOException("Negative position");
		}
		copy.seek(position);
		copy.write(value);
		changed = true;
	}

	@Override
	public void write(long position, byte[] b, int off, int len) throws IOException {
		java.util.Objects.checkFromIndexSize(off, len, b.length);
		checkOpen();
		if( position < 0 ) {
			throw new IOException("Negative position");
		}
		copy.seek(position);
		copy.write(b, off, len);
		changed = true;
	}

	@Override
	public long length() throws IOException {
		checkOpen();
		return copy.length();
	}

	@Override
	public void setLength(long newLength) throws IOException {
		checkOpen();
		if( newLength < 0 ) {
			throw new IOException("Negative length "+newLength);
		}
		if( newLength != copy.length() ) {
			copy.setLength(newLength);
			changed = true;
		}
	}

	/** Writes go to the local copy at once: nothing to save until it is sent back. */
	@Override
	public void save() throws IOException {
		checkOpen();
	}

	/** Sends the copy back to the server if anything was written since it was last sent. */
	@Override
	public void sync() throws IOException {
		checkOpen();
		storeIfChanged();
	}

	private void storeIfChanged() throws IOException {
		if( changed ) {
			copy.getFD().sync();
			dav.store(local);
			changed = false;
		}
	}

	@Override
	public FileSource getFile() {
		return dav;
	}

	/** Sends the copy back if anything was written, then deletes it. */
	@Override
	public void close() throws IOException {
		if( closed ) {
			return;
		}
		try {
			storeIfChanged();
		} finally {
			closed = true;
			try {
				copy.close();
			} finally {
				Files.deleteIfExists(local);
			}
		}
	}
}
