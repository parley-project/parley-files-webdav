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
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The body of a PUT, sent as it is written: what is written goes through a buffer to the HTTP
 * client, which sends it to the server (chunked), so a file of any size is never held in memory.
 * <p>
 * The server's answer is checked: {@link #close()} waits for it and throws unless it is a success
 * (2xx), so an upload the server refused or couldn't store (no folder, no permission, a full disk,
 * a lock) is never taken for one that worked. If the server answers before the upload has ended
 * (it refused it at once), the next write throws with what it said. Then the callback given to
 * {@link #whenStored(Check)} is run (the FileSource checks the stored size there).
 */
final class WebDavOutputStream extends OutputStream {

	/** Run after the server has said the upload is stored, with the number of bytes sent. */
	interface Check {
		void stored(long bytes) throws IOException;
	}

	private final Pipe pipe;
	private final CompletableFuture<HttpResponse<byte[]>> answer;
	private final String method;
	private final String path;
	private final byte[] one = new byte[1];
	private long count;
	private boolean closed;
	private Check whenStored;
	private Runnable whenClosed;

	WebDavOutputStream(Pipe pipe, CompletableFuture<HttpResponse<byte[]>> answer, String method, String path) {
		this.pipe = pipe;
		this.answer = answer;
		this.method = method;
		this.path = path;
		answer.whenComplete((res, error) -> pipe.answered(describe(res, error)));
	}

	/** null for a success, else what went wrong. */
	private IOException describe(HttpResponse<byte[]> res, Throwable error) {
		if( error != null ) {
			Throwable e = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
			if( e instanceof IOException ) {
				return new IOException(method + " " + path + " failed: " + e.getMessage(), e);
			}
			return new IOException(method + " " + path + " failed: " + e, e);
		}
		if( res.statusCode() / 100 != 2 ) {
			return WebDavClient.failure(method, path, res.statusCode(), res.body());
		}
		return null;
	}

	/** @param check run when the server has stored the upload; it may throw to say it wasn't stored right */
	WebDavOutputStream whenStored(Check check) {
		this.whenStored = check;
		return this;
	}

	/** @param r run when the stream is closed, whether the upload worked or not */
	WebDavOutputStream whenClosed(Runnable r) {
		this.whenClosed = r;
		return this;
	}

	/** Fails the upload: the server gets an incomplete request, and the next write or close throws. */
	void abort(IOException why) {
		pipe.abort(why);
	}

	/** @return the bytes written so far */
	long getCount() {
		return count;
	}

	@Override
	public void write(int b) throws IOException {
		one[0] = (byte) b;
		write(one, 0, 1);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		Objects.checkFromIndexSize(off, len, b.length);
		if( closed ) {
			throw new IOException("Stream closed");
		}
		pipe.write(b, off, len);
		count += len;
	}

	/**
	 * Ends the upload and waits for the server's answer.
	 *
	 * @throws IOException if the server didn't store it (its status and what it said), or the check
	 *         after it failed
	 */
	@Override
	public void close() throws IOException {
		if( closed ) {
			return;
		}
		closed = true;
		try {
			pipe.closeWriter();
			HttpResponse<byte[]> res;
			try {
				res = answer.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				answer.cancel(true);
				InterruptedIOException ie = new InterruptedIOException(method + " " + path + " was interrupted");
				ie.initCause(e);
				throw ie;
			} catch (ExecutionException | CancellationException e) {
				IOException ret = describe(null, e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e);
				throw ret;
			}
			IOException failed = describe(res, null);
			if( failed != null ) {
				throw failed;
			}
			IOException early = pipe.earlyAnswer();
			if( early != null ) {
				throw early;
			}
			if( whenStored != null ) {
				whenStored.stored(count);
			}
		} finally {
			if( whenClosed != null ) {
				whenClosed.run();
			}
		}
	}

	// ------------------------------------------------------------------ the buffer between the writer and the HTTP client

	/**
	 * A bounded buffer: the writer's thread puts bytes in, the HTTP client's thread takes them out.
	 * A write waits while it is full; one that waits longer than the timeout with nothing taken
	 * (a server that stopped reading) fails.
	 */
	static final class Pipe {
		private final byte[] buf;
		private final long stallMillis;
		private int head;
		private int count;
		private boolean writerClosed;
		private boolean readerClosed;
		/** Why the writer must stop: the server answered (a failure) or the connection failed. */
		private IOException failure;
		/** Why the upload was failed on this side (a disconnect), or null. */
		private IOException aborted;
		/** The server answered with a success before the writer was closed. */
		private IOException early;
		private boolean handedOut;
		private long lastTaken = System.currentTimeMillis();

		Pipe(int size, Duration stall) {
			buf = new byte[Math.max(1024, size)];
			stallMillis = stall == null || stall.isZero() || stall.isNegative() ? 0 : stall.toMillis();
		}

		/** The HTTP client's side: once. A second call (a retry) would send what is left as if it were the whole file. */
		synchronized InputStream reader() {
			if( handedOut ) {
				return new InputStream() {
					@Override
					public int read() throws IOException {
						throw new IOException("An upload can't be sent twice");
					}
				};
			}
			handedOut = true;
			return new Reader();
		}

		synchronized void write(byte[] b, int off, int len) throws IOException {
			while( len > 0 ) {
				long waitedSince = System.currentTimeMillis();
				while( count == buf.length && failure == null && !readerClosed ) {
					long left = stallMillis == 0 ? 0 : stallMillis - (System.currentTimeMillis() - Math.max(waitedSince, lastTaken));
					if( stallMillis != 0 && left <= 0 ) {
						IOException e = new SocketTimeoutException("The server took nothing for "
								+ WebDavFileSourceFactory.describe(Duration.ofMillis(stallMillis)) + " (timeout)");
						failure = e;
						aborted = e;
						notifyAll();
						throw e;
					}
					try {
						wait(stallMillis == 0 ? 0 : left);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						InterruptedIOException ie = new InterruptedIOException("Upload interrupted");
						failure = ie;
						aborted = ie;
						notifyAll();
						throw ie;
					}
				}
				if( failure != null ) {
					throw new IOException(failure.getMessage(), failure);
				}
				if( readerClosed ) {
					throw new IOException("The server stopped taking the upload");
				}
				int tail = (head + count) % buf.length;
				int n = Math.min(len, Math.min(buf.length - count, buf.length - tail));
				System.arraycopy(b, off, buf, tail, n);
				count += n;
				off += n;
				len -= n;
				notifyAll();
			}
		}

		/** Fails the upload on this side: the HTTP client's reads throw, so the request is never completed. */
		synchronized void abort(IOException why) {
			if( aborted == null ) {
				aborted = why;
			}
			if( failure == null ) {
				failure = why;
			}
			notifyAll();
		}

		synchronized void closeWriter() {
			writerClosed = true;
			notifyAll();
		}

		/** The server's answer has come: null for a success, else what went wrong. */
		synchronized void answered(IOException problem) {
			if( problem != null ) {
				if( failure == null ) {
					failure = problem;
				}
			} else if( !writerClosed ) {
				early = new IOException("The server answered before the upload ended, so not all of it may be stored");
				if( failure == null ) {
					failure = early;
				}
			}
			notifyAll();
		}

		synchronized IOException earlyAnswer() {
			return early;
		}

		private final class Reader extends InputStream {
			@Override
			public int read() throws IOException {
				byte[] b = new byte[1];
				int n = read(b, 0, 1);
				return n < 0 ? -1 : b[0] & 0xff;
			}

			@Override
			public int read(byte[] b, int off, int len) throws IOException {
				synchronized (Pipe.this) {
					while( count == 0 && !writerClosed && failure == null ) {
						try {
							Pipe.this.wait();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new InterruptedIOException();
						}
					}
					if( aborted != null ) {
						// failed on this side: the server must not take what was sent as the whole file
						throw new IOException(aborted.getMessage(), aborted);
					}
					if( count == 0 ) {
						if( failure != null && !writerClosed ) {
							throw new IOException(failure.getMessage(), failure);
						}
						return -1;
					}
					int n = Math.min(len, Math.min(count, buf.length - head));
					System.arraycopy(buf, head, b, off, n);
					head = (head + n) % buf.length;
					count -= n;
					lastTaken = System.currentTimeMillis();
					Pipe.this.notifyAll();
					return n;
				}
			}

			@Override
			public void close() {
				synchronized (Pipe.this) {
					readerClosed = true;
					Pipe.this.notifyAll();
				}
			}
		}
	}
}
