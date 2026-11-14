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
import java.io.InputStream;
import java.util.Objects;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.ISeekableInputStream;

/**
 * A read-only view of a WebDAV file that can be moved around, as a RandomAccessFile opened with
 * "r": bytes are 0..255, seeking past the end only moves the pointer (the next read is -1) and
 * never changes the file.
 * <p>
 * One GET is kept open at the pointer: reading on from where the last read ended continues it. A
 * seek costs nothing by itself; the next read either reads through a short step forward on the open
 * GET, or drops it and starts another at the new place with a range request (a request each time,
 * so reading at random is slow).
 */
final class WebDavSeekableInputStream extends InputStream implements ISeekableInputStream {

	/** A step forward of at most this many bytes is read through, which is cheaper than a new GET. */
	static final long READ_THROUGH_LIMIT = 256 * 1024;

	private final WebDavFileSource file;
	private long pointer;
	private boolean closed;
	/** the GET in use and where it is; null if there isn't one */
	private InputStream current;
	private long currentAt = -1;
	private final byte[] one = new byte[1];

	WebDavSeekableInputStream(WebDavFileSource file) {
		this.file = file;
	}

	@Override
	public long length() throws IOException {
		// a file is as long as it is now, not as long as it was when it was last looked at
		file.refresh();
		return file.length();
	}

	@Override
	public void seek(long whereTo) throws IOException {
		if( whereTo < 0 ) {
			throw new IOException("Negative seek offset");
		}
		pointer = whereTo;
	}

	@Override
	public int read() throws IOException {
		return read(one, 0, 1) == 1 ? one[0] & 0xff : -1;
	}

	@Override
	public int read(byte[] data) throws IOException {
		return read(data, 0, data.length);
	}

	@Override
	public int read(byte[] data, int off, int len) throws IOException {
		Objects.checkFromIndexSize(off, len, data.length);
		if( closed ) {
			return -1;
		}
		if( len == 0 ) {
			return 0;
		}
		position();
		int n = current.read(data, off, len);
		if( n > 0 ) {
			pointer += n;
			currentAt += n;
		}
		return n;
	}

	/** Makes the GET in use be at the pointer: continue it, read it through, or start another. */
	private void position() throws IOException {
		if( current != null ) {
			if( currentAt == pointer ) {
				return;
			}
			long step = pointer - currentAt;
			if( step > 0 && step <= READ_THROUGH_LIMIT ) {
				byte[] sink = new byte[(int) Math.min(step, 8192)];
				while( step > 0 ) {
					int r = current.read(sink, 0, (int) Math.min(sink.length, step));
					if( r < 0 ) {
						// the end is before the pointer: reads from here are -1
						currentAt = pointer;
						return;
					}
					step -= r;
					currentAt += r;
				}
				return;
			}
			drop();
		}
		current = file.getInputStream(pointer, -1);
		currentAt = pointer;
	}

	private void drop() throws IOException {
		InputStream c = current;
		current = null;
		currentAt = -1;
		if( c != null ) {
			c.close();
		}
	}

	@Override
	public void close() throws IOException {
		closed = true;
		drop();
	}

	@Override
	public long getFilePointer() throws IOException {
		return pointer;
	}

	@Override
	public FileSource getFile() throws IOException {
		return file;
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return this;
	}
}
