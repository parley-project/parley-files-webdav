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
import java.util.Arrays;

import us.bringardner.parley.files.AbstractRandomAccessIoController;

/**
 * Read-only random access to a WebDAV file (mode "r"), in chunks (the factory's buffer size unless
 * the stream was opened with a chunk size): each chunk is a GET with a range (RFC 9110 section 14).
 * A server that ignores ranges sends the whole file each time, of which the chunk is kept, so reading
 * at random from such a server is slow.
 * <p>
 * Writing is refused here: a stream opened for writing is a {@link WebDavLocalCopyIoController}.
 */
public class WebDavRandomAccessIoController extends AbstractRandomAccessIoController {

	private final WebDavFileSource dav;
	/** Bytes one range request reads; fixed when the stream was opened. */
	private final int chunkSize;

	/**
	 * @param chunkSize bytes one request reads, and the size of the chunk that is cached; 0 for the
	 *        factory's buffer size
	 * @throws FileNotFoundException if it's missing or a directory
	 */
	public WebDavRandomAccessIoController(WebDavFileSource file, int chunkSize) throws IOException {
		super(file);
		this.dav = file;
		WebDavFileSourceFactory f = (WebDavFileSourceFactory) file.getFileSourceFactory();
		this.chunkSize = chunkSize > 0 ? Math.min(chunkSize, WebDavFileSourceFactory.MAX_BUFFER_SIZE) : f.getBufferSize();
		file.refresh();
		if( !file.exists() ) {
			throw new FileNotFoundException(file.getAbsolutePath()+" (No such file or directory)");
		}
		if( file.isDirectory() ) {
			throw new FileNotFoundException(file.getAbsolutePath()+" (Is a directory)");
		}
	}

	/** true: the constructor checked the file on the server. */
	@Override
	public boolean checksAccessOnOpen() {
		return true;
	}

	/** @return the chunk size this stream reads in */
	public int getChunkSize() {
		return chunkSize;
	}

	@Override
	protected Chunk readChunkForPos(long pos) throws IOException {
		Chunk ret = new Chunk();
		long len = length();
		if( len == 0 || pos >= len ) {
			ret.size = 0;
			ret.data = new byte[chunkSize];
			ret.start = len;
			ret.isNew = true;
		} else {
			long chunk = pos/chunkSize;
			ret.start = chunk * chunkSize;
			ret.chunkNumber = chunk;
			ret.data = readBlock(ret.start, (int)Math.min(chunkSize, len-ret.start));
			ret.size = ret.data.length;
		}
		return ret;
	}

	/** A GET of the bytes from start, want of them (a response may come in pieces). */
	private byte[] readBlock(long start, int want) throws IOException {
		byte[] ret = new byte[want];
		int got = 0;
		try (InputStream in = dav.getInputStream(start, start + want - 1)) {
			while( got < want ) {
				int n = in.read(ret, got, want-got);
				if( n < 0 ) {
					break;
				}
				got += n;
			}
		}
		if( got == 0 ) {
			throw new IOException("Unexpected end of file at "+start+" (length was "+length()
					+"); was "+dav.getAbsolutePath()+" changed while it was open?");
		}
		return got == want ? ret : Arrays.copyOf(ret, got);
	}

	@Override
	public void write(long pos, byte value) throws IOException {
		throw new IOException("Opened read-only (mode \"r\")");
	}

	@Override
	protected void writeChunk(Chunk chunk) throws IOException {
		throw new IOException("Opened read-only (mode \"r\")");
	}

	@Override
	protected void setLength0(long newLength) throws IOException {
		throw new IOException("Opened read-only (mode \"r\")");
	}
}
