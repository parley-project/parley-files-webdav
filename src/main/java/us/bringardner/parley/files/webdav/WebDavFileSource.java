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
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceFilter;
import us.bringardner.parley.files.FileSourceProgress;
import us.bringardner.parley.files.FileSourceRandomAccessStream;
import us.bringardner.parley.files.IRandomAccessIoController;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.files.StreamOption;
import us.bringardner.parley.files.StreamOptions;
import us.bringardner.parley.files.fileproxy.FileProxy;

/**
 * A file or folder on a WebDAV server (RFC 4918). Paths are from the factory's root setting: "/" is
 * the folder the root names.
 * <p>
 * What the server says of a file (a PROPFIND) is remembered for the factory's attribute cache time;
 * a change made through the factory is seen at once (see {@link WebDavFileSourceFactory#noteChange(String...)}).
 * <p>
 * WebDAV has no permission bits, owners or links: the owner's read and write are true for a file
 * that's there and everything else is false (the server decides when a read or write is tried);
 * none can be set, and {@link #getLinkedTo()} is null.
 */
public class WebDavFileSource extends BaseObject implements FileSource {

	private static final long serialVersionUID = 1L;
	private static final String SEPARATOR = "/";
	private static final char SEPARATOR_CHAR = '/';

	private WebDavFileSourceFactory factory;
	private String name;
	private String parent;

	private volatile WebDavFile target;
	/**
	 * The factory's {@link WebDavFileSourceFactory#changeStamp()} taken before target and kids_ were
	 * read: a change made through the factory since then, to this path or a folder it is in (or for
	 * kids_, to what is in this folder), drops them. Other paths' changes don't.
	 */
	private volatile long targetStamp;
	private volatile long kidsStamp;
	/** When target and kids_ were read: they are trusted for the factory's attribute cache time. */
	private volatile long targetAt;
	private volatile long kidsAt;
	private volatile WebDavFileSource parentFile;
	private volatile FileSource[] kids_;

	/** @param name a path; see {@link #normalize(String)} (the factory resolves relative ones first) */
	public WebDavFileSource(String name, WebDavFileSourceFactory factory) {
		this.factory = factory;
		initPath(name);
	}

	/** A child of a folder: name may be a relative path; "." and ".." are resolved. */
	public WebDavFileSource(WebDavFileSource parentFile, String path) {
		this.factory = (WebDavFileSourceFactory) parentFile.getFileSourceFactory();
		String parentPath = parentFile.getAbsolutePath();
		initPath(parentPath+SEPARATOR+path);
		if( parentPath.equals(parent)) {
			// a plain name: keep the parent object (otherwise it's made when asked for)
			this.parentFile = parentFile;
		}
	}

	/** A member of a listing: what the listing said of it is known. */
	WebDavFileSource(WebDavFileSource p, WebDavFile t, long stamp, long at) {
		this.factory = p.factory;
		initPath(p.getAbsolutePath()+SEPARATOR+t.getName());
		known(t, stamp, at);
		this.parentFile = p;
	}

	private void forgetIfChanged() {
		// something that was remembered too long ago may have been changed by someone else
		long ttl = factory.getAttributeCacheTtl();
		long now = System.currentTimeMillis();
		String path = null;
		if( target != null ) {
			path = pathOf(this);
			if( now - targetAt > ttl || factory.changedSince(path, targetStamp, false) ) {
				target = null;
			}
		}
		if( kids_ != null ) {
			if( path == null ) {
				path = pathOf(this);
			}
			if( now - kidsAt > ttl || factory.changedSince(path, kidsStamp, true) ) {
				kids_ = null;
			}
		}
	}

	/** Sets what is known of this file, read from the server after the factory's stamp was taken. */
	private void known(WebDavFile file, long stamp, long at) {
		this.targetStamp = stamp;
		this.targetAt = at;
		this.target = file;
	}

	@Override
	public void logDebug(String msg) {factory.logDebug(msg);}
	@Override
	public void logDebug(String msg, Throwable error) {
		factory.logDebug(msg, error);
	}

	@Override
	public void logError(String msg) {
		factory.logError(msg);
	}

	@Override
	public void logError(String msg, Throwable error) {
		factory.logError(msg, error);
	}

	@Override
	public void logInfo(String msg) {
		factory.logInfo(msg);
	}

	@Override
	public void logInfo(String msg, Throwable error) {
		factory.logInfo(msg, error);
	}

	@Override
	public boolean isDebugEnabled() {
		return factory.isDebugEnabled();
	}

	@Override
	public boolean isErrorEnabled() {
		return factory.isErrorEnabled();
	}

	@Override
	public boolean isInfoEnabled() {
		return factory.isInfoEnabled();
	}

	// ---- what the server says of the file

	/** @return what the server said of this path, or null if there's nothing there */
	private WebDavFile getTarget() throws IOException {
		WebDavFile writing = factory.uploading(pathOf(this));
		if( writing != null ) {
			// being uploaded through this factory: not asked (see WebDavFileSourceFactory.uploading)
			return writing;
		}
		forgetIfChanged();
		if(target == null) {
			synchronized(this) {
				if(target == null ) {
					String path = getAbsolutePath();
					// a recent listing of the folder it is in, if it has this name
					WebDavFileSourceFactory.RecentListing l = parent == null ? null : factory.recentListing(parent);
					WebDavFile f = l == null ? null : l.get(name);
					if( f != null && !factory.changedSince(path, l.stamp, false) ) {
						known(f, l.stamp, l.at);
					} else {
						long stamp = factory.changeStamp();
						known(factory.getClient().stat(path), stamp, System.currentTimeMillis());
					}
				}
			}
		}
		return target;
	}

	private WebDavClient client() throws IOException {
		return factory.getClient();
	}

	public boolean exists() throws IOException {
		// A missing file is looked up again each time: it may have been created since.
		return getTarget() != null;
	}

	public boolean isDirectory() throws IOException {
		WebDavFile tmp = getTarget();
		return tmp != null && tmp.isDirectory();
	}

	public boolean isFile() throws IOException {
		WebDavFile tmp = getTarget();
		return tmp != null && tmp.isFile();
	}

	public long length() throws IOException {
		WebDavFile tmp = getTarget();
		return tmp == null ? 0 : tmp.getLength();
	}

	/** DAV:getlastmodified, in whole seconds; 0 if the server doesn't say. */
	public long lastModified() throws IOException {
		WebDavFile tmp = getTarget();
		return tmp == null ? 0 : tmp.getLastModified();
	}

	/** DAV:creationdate; 0 if the server doesn't say. */
	@Override
	public long creationTime() throws IOException {
		WebDavFile tmp = getTarget();
		return tmp == null ? 0 : tmp.getCreated();
	}

	public long getCreateDate() throws IOException {
		return creationTime();
	}

	/** WebDAV doesn't keep the time a file was last read */
	@Override
	public long lastAccessTime() throws IOException {
		return 0;
	}

	/** The server's DAV:getcontenttype when it said, else one from the name. */
	public String getContentType() {
		WebDavFile tmp = target;
		if( tmp != null && tmp.getContentType() != null && !tmp.getContentType().isEmpty() ) {
			return tmp.getContentType();
		}
		return FileProxy.getContentType(getName());
	}

	/** @return the server's entity tag (DAV:getetag) for the file, or null */
	public String getEtag() throws IOException {
		WebDavFile tmp = getTarget();
		return tmp == null ? null : tmp.getEtag();
	}

	// ---- permissions: WebDAV has none

	/** As java.io.File: nothing can be read from or written to at a path that doesn't exist. */
	@Override
	public boolean canRead() throws IOException {
		return exists();
	}

	@Override
	public boolean canWrite() throws IOException {
		return exists();
	}

	@Override
	public boolean canExecute() throws IOException {
		return false;
	}

	/** True when it is there: WebDAV has no permission bits, and the server decides when it is read. */
	@Override
	public boolean canOwnerRead() throws IOException {
		return exists();
	}

	/** True when it is there: WebDAV has no permission bits, and the server decides when it is written. */
	@Override
	public boolean canOwnerWrite() throws IOException {
		return exists();
	}

	@Override
	public boolean canOwnerExecute() throws IOException {
		return false;
	}

	@Override
	public boolean canGroupRead() throws IOException {
		return false;
	}

	@Override
	public boolean canGroupWrite() throws IOException {
		return false;
	}

	@Override
	public boolean canGroupExecute() throws IOException {
		return false;
	}

	@Override
	public boolean canOtherRead() throws IOException {
		return false;
	}

	@Override
	public boolean canOtherWrite() throws IOException {
		return false;
	}

	@Override
	public boolean canOtherExecute() throws IOException {
		return false;
	}

	@Override
	public boolean setExecutable(boolean executable) throws IOException {
		return false;
	}

	@Override
	public boolean setReadable(boolean readable) throws IOException {
		return false;
	}

	@Override
	public boolean setWritable(boolean writable) throws IOException {
		return false;
	}

	@Override
	public boolean setReadable(boolean readable, boolean ownerOnly) throws IOException {
		return false;
	}

	@Override
	public boolean setWritable(boolean writable, boolean ownerOnly) throws IOException {
		return false;
	}

	@Override
	public boolean setExecutable(boolean executable, boolean ownerOnly) throws IOException {
		return false;
	}

	@Override
	public boolean setOwnerExecutable(boolean b) throws IOException {
		return false;
	}

	@Override
	public boolean setOwnerReadable(boolean b) throws IOException {
		return false;
	}

	@Override
	public boolean setOwnerWritable(boolean b) throws IOException {
		return false;
	}

	@Override
	public boolean setGroupReadable(boolean b) throws IOException {
		return false;
	}

	@Override
	public boolean setGroupWritable(boolean b) throws IOException {
		return false;
	}

	@Override
	public boolean setGroupExecutable(boolean b) throws IOException {
		return false;
	}

	@Override
	public boolean setOtherReadable(boolean b) throws IOException {
		return false;
	}

	@Override
	public boolean setOtherWritable(boolean b) throws IOException {
		return false;
	}

	@Override
	public boolean setOtherExecutable(boolean b) throws IOException {
		return false;
	}

	/** WebDAV's DAV:getlastmodified is the server's to set */
	@Override
	public boolean setReadOnly() throws IOException {
		return false;
	}

	/** WebDAV's DAV:getlastmodified is the server's to set */
	public boolean setLastModified(long time) throws IOException {
		return false;
	}

	/** WebDAV's DAV:getlastmodified is the server's to set */
	@Override
	public boolean setLastModifiedTime(long time) throws IOException {
		return false;
	}

	@Override
	public boolean setLastAccessTime(long time) throws IOException {
		return false;
	}

	/** WebDAV's DAV:creationdate is the server's to set */
	@Override
	public boolean setCreateTime(long time) throws IOException {
		return false;
	}

	/** WebDAV says nothing of owners (RFC 3744's ACLs aren't used): the name is null. */
	@Override
	public UserPrincipal getOwner() throws IOException {
		return () -> null;
	}

	/** WebDAV says nothing of groups: the name is null. */
	@Override
	public GroupPrincipal getGroup() throws IOException {
		return () -> null;
	}

	@Override
	public boolean setGroup(GroupPrincipal group) throws IOException {
		return false;
	}

	@Override
	public boolean setOwner(UserPrincipal owner) throws IOException {
		return false;
	}

	// ---- capabilities

	/**
	 * WebDAV: locks reach this JVM only; createNewFile is a PUT with If-None-Match: *, atomic only
	 * where the server keeps to it (not claimed); mkdir is (MKCOL); replace isn't; one server, so
	 * operations are applied in one order.
	 */
	@Override
	public us.bringardner.parley.files.FileSourceCapabilities getCapabilities() {
		return us.bringardner.parley.files.FileSourceCapabilities.defaults(getLockScope()).withAtomicCreateNewFile(false)
				.withOrderedOperations(true);
	}

	// ---- making, deleting, renaming

	/**
	 * As java.io.File: true if the file was created, false if something was already there, and an
	 * IOException if the folder it goes in isn't. A PUT of nothing with {@code If-None-Match: *}, so a
	 * server that keeps to HTTP never empties a file another client made meanwhile.
	 */
	public boolean createNewFile() throws IOException {
		if( exists() ) {
			return false;
		}
		FileSource p = getParentFile();
		if( p == null || !p.isDirectory() ) {
			throw new IOException("No such file or directory: "+getParent());
		}
		try {
			return client().createEmpty(getAbsolutePath());
		} finally {
			forgetWhatIsKnown();
		}
	}

	/**
	 * As java.io.File: false for a folder that isn't empty (WebDAV's DELETE would take everything in
	 * it) and for nothing there.
	 */
	public boolean delete() throws IOException {
		WebDavFile tmp = getTarget();
		if( tmp == null ) {
			return false;
		}
		if( tmp.isDirectory() ) {
			FileSource[] kids = listFiles();
			if( kids != null && kids.length > 0 ) {
				return false;
			}
		}
		boolean ret = false;
		try {
			ret = client().delete(getAbsolutePath(), tmp.isDirectory());
		} catch (IOException ex) {
			logError("Error in delete", ex);
		} finally {
			forgetWhatIsKnown();
		}
		return ret;
	}

	/**
	 * MKCOL for this path (one level, as java.io.File.mkdir): false if something is there or the
	 * folder it goes in isn't.
	 */
	public boolean mkdir() throws IOException {
		if( exists() ) {
			return false;
		}
		FileSource p = getParentFile();
		if( p != null && !p.isDirectory() ) {
			return false;
		}
		boolean ret = false;
		try {
			ret = client().mkcol(getAbsolutePath());
		} catch (IOException ex) {
			logError("Error in mkdir", ex);
		} finally {
			kids_ = null;
			forgetWhatIsKnown();
		}
		return ret;
	}

	/** MKCOL for each missing folder on the way, from the top; as java.io.File, false if it was there. */
	public boolean mkdirs() throws IOException {
		if( exists() ) {
			return false;
		}
		List<WebDavFileSource> missing = new ArrayList<>();
		for( WebDavFileSource f = this; f != null && !f.exists(); f = (WebDavFileSource) f.getParentFile() ) {
			missing.add(0, f);
		}
		for(WebDavFileSource f : missing) {
			if( !f.mkdir() && !f.isDirectory() ) {
				return false;
			}
		}
		return isDirectory();
	}

	/**
	 * MOVE with "Overwrite: F", to a path of the same server, root and account: false if something is
	 * already there (use {@link #renameTo(FileSource, boolean)} to replace a file).
	 */
	public boolean renameTo(FileSource dest) {
		if( !(dest instanceof WebDavFileSource) || !((WebDavFileSource) dest).factory.isSameFileSystem(factory) ) {
			return false;
		}
		WebDavFileSource d = (WebDavFileSource) dest;
		boolean ret = false;
		try {
			WebDavFile tmp = getTarget();
			if( tmp == null ) {
				return false;
			}
			if( pathOf(this).equals(pathOf(d)) ) {
				// as java.io.File: renaming a file to itself is a success that changes nothing
				return true;
			}
			if( isChildOfMine(d) ) {
				// a folder into itself
				return false;
			}
			ret = client().move(pathOf(this), pathOf(d), tmp.isDirectory());
		} catch (IOException ex) {
			logError("Error in rename", ex);
		} finally {
			kids_ = null;
			d.kids_ = null;
			d.target = null;
			forgetWhatIsKnown();
			factory.noteChange(pathOf(d));
		}
		return ret;
	}

	/** After a change: what is known of this file and its folder's listing is looked up again. */
	private void forgetWhatIsKnown() {
		FileSource p = parentFile;
		if( p instanceof WebDavFileSource ) {
			((WebDavFileSource) p).kids_ = null;
		}
		target = null;
		factory.noteChange(pathOf(this));
	}

	// ---- listing

	public String[] list() throws IOException {
		return list((FileSourceFilter)null);
	}

	public String[] list(FileSourceFilter filter) throws IOException {
		FileSource [] list = listFiles(filter);
		if( list == null ) {
			return null;
		}
		String[] ret = new String[list.length];
		for (int idx = 0; idx < list.length; idx++) {
			ret[idx] = list[idx].getName();
		}
		return ret;
	}

	public FileSource[] listFiles() throws IOException {
		return listFiles((FileSourceProgress)null);
	}

	public FileSource[] listFiles(FileSourceFilter filter) throws IOException {
		FileSource[] list = listFiles();
		if( list == null || filter == null ) {
			return list;
		}
		List<FileSource> tmp = new ArrayList<FileSource>();
		for (FileSource f : list) {
			if(filter.accept(f)) {
				tmp.add(f);
			}
		}
		return tmp.toArray(new FileSource[tmp.size()]);
	}

	/** PROPFIND with Depth 1; null (as java.io.File) for a file or nothing there. */
	public FileSource[] listFiles(FileSourceProgress progress) throws IOException {
		forgetIfChanged();
		if( kids_ == null ) {
			synchronized (this) {
				if( kids_ == null ) {
					String path = getAbsolutePath();
					WebDavFileSourceFactory.RecentListing recent = factory.recentListing(path);
					if( recent != null ) {
						// listed lately (by another FileSource for this folder): no need to ask
						kids_ = members(recent.files, recent.stamp, recent.at);
						kidsStamp = recent.stamp;
						kidsAt = recent.at;
					} else if( isDirectory() ) {
						long stamp = factory.changeStamp();
						List<WebDavFile> list = client().list(path);
						long at = System.currentTimeMillis();
						if( list == null ) {
							// gone since it was looked at
							target = null;
							return null;
						}
						WebDavFile[] files = list.toArray(new WebDavFile[0]);
						factory.keepListing(path, files, stamp, at);
						kids_ = members(files, stamp, at);
						kidsStamp = stamp;
						kidsAt = at;
					}
				}
			}
		}
		return kids_;
	}

	private FileSource[] members(WebDavFile[] files, long stamp, long at) {
		FileSource[] ret = new FileSource[files.length];
		for (int idx = 0; idx < files.length; idx++) {
			ret[idx] = new WebDavFileSource(this, files[idx], stamp, at);
		}
		return ret;
	}

	/**
	 * Reads this file again, and (for a folder) its listing; for every FileSource of the factory at
	 * this path, not only this one. Nothing else is read again.
	 */
	public void refresh() throws IOException {
		target = null;
		kids_ = null;
		factory.noteListingChange(pathOf(this));
		getTarget();
	}

	public void dereferenceChilderen() {
		kids_ = null;
	}

	// ---- streams

	public InputStream getInputStream() throws IOException {
		return getInputStream(0L);
	}

	/** A GET from startingPos (a range request), on a connection of the client's pool. */
	public InputStream getInputStream(long startingPos) throws IOException {
		return getInputStream(startingPos, -1);
	}

	/**
	 * The bytes from start to end (inclusive; -1 for the end of the file): a GET with a range. Used by
	 * {@link WebDavRandomAccessIoController} and {@link WebDavSeekableInputStream}.
	 */
	InputStream getInputStream(long start, long end) throws IOException {
		WebDavFile tmp = getTarget();
		if( tmp == null ) {
			throw new FileNotFoundException(getAbsolutePath()+" does not exist");
		}
		if( tmp.isDirectory() ) {
			throw new IOException("Can't create stream from directory");
		}
		if( start < 0 ) {
			throw new IOException("Negative position");
		}
		try {
			return client().get(getAbsolutePath(), start, end);
		} catch (FileNotFoundException e) {
			// gone since it was looked at
			forgetWhatIsKnown();
			throw e;
		}
	}

	public OutputStream getOutputStream() throws IOException {
		return getOutputStream(false);
	}

	public OutputStream getOutputStream(boolean append) throws IOException {
		return openOutput(append, 0);
	}

	/**
	 * A PUT, streamed as it is written. Closing it waits for the server's answer and throws unless
	 * the file was stored, at the size written.
	 * <p>
	 * WebDAV can't add to a file: appending downloads the file to a local temporary file first and
	 * sends it all again, followed by what is written.
	 *
	 * @param bufferSize what the stream holds before a write waits for the server, or 0 for the factory's
	 * @throws FileNotFoundException if this is a folder or the folder it goes in isn't there, as
	 *         java.io.FileOutputStream
	 */
	private OutputStream openOutput(boolean append, int bufferSize) throws IOException {
		String path = getAbsolutePath();
		WebDavFile tmp = getTarget();
		if( tmp != null && tmp.isDirectory() ) {
			throw new FileNotFoundException(path+" (Is a directory)");
		}
		FileSource p = getParentFile();
		if( p != null && !p.isDirectory() ) {
			throw new FileNotFoundException(path+" (No such file or directory)");
		}
		int size = bufferSize > 0 ? bufferSize : factory.getBufferSize();
		Path old = null;
		try {
			if( append && tmp != null && tmp.getLength() > 0 ) {
				old = Files.createTempFile("parley-webdav-append-", ".tmp");
				try (InputStream in = client().get(path, 0, -1)) {
					Files.copy(in, old, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
			WebDavOutputStream out = client().put(path, size, false);
			out.whenStored(this::checkStored).whenClosed(() -> {
				factory.uploadEnded(path, out);
				forgetWhatIsKnown();
			});
			factory.uploadStarted(path, out);
			if( old != null ) {
				try {
					Files.copy(old, out);
				} catch (IOException | RuntimeException e) {
					try {
						out.close();
					} catch (IOException e2) {
						e.addSuppressed(e2);
					}
					throw e;
				}
			}
			return out;
		} finally {
			// what is known of it is out of date once it is opened for writing
			forgetWhatIsKnown();
			if( old != null ) {
				Files.deleteIfExists(old);
			}
		}
	}

	/**
	 * After an upload: the server must have stored as many bytes as were sent. A server that cut it
	 * short and still said it was stored is reported.
	 */
	private void checkStored(long bytes) throws IOException {
		target = null;
		WebDavFile now = client().stat(getAbsolutePath());
		if( now == null ) {
			throw new IOException(getAbsolutePath()+" isn't on the server after it was stored ("+bytes+" bytes sent)");
		}
		if( now.getLength() != bytes ) {
			throw new IOException("The server stored "+now.getLength()+" bytes of "+getAbsolutePath()
					+" after "+bytes+" were sent: the upload is damaged");
		}
	}

	/**
	 * A PUT of the whole file from a local one (a random access stream's copy), at its known length,
	 * checked as {@link #getOutputStream()} is.
	 */
	void store(Path local) throws IOException {
		long bytes = Files.size(local);
		try {
			client().put(getAbsolutePath(), java.net.http.HttpRequest.BodyPublishers.ofFile(local));
			checkStored(bytes);
		} finally {
			forgetWhatIsKnown();
		}
	}

	// ---- streams with their own buffer size (see StreamOptions)

	/**
	 * An upload's buffer (how much it holds before a write waits for the server) is a setting of
	 * every stream here. A random access stream also takes a chunk size: how much one range request reads.
	 */
	@Override
	public java.util.Set<StreamOption<?>> supportedStreamOptions() {
		return java.util.Set.of(StreamOption.BUFFER_SIZE, StreamOption.CHUNK_SIZE);
	}

	/** The factory's buffer size, which a stream opened without one uses. */
	@Override
	public StreamOptions getStreamDefaults() {
		return StreamOptions.buffer(factory.getBufferSize());
	}

	/** The buffer size asked for, kept to what the factory allows; 0 when none was. */
	private static int bufferSizeIn(StreamOptions options) {
		int size = StreamOptions.orNone(options).bufferSize();
		return size > 0 ? Math.min(size, WebDavFileSourceFactory.MAX_BUFFER_SIZE) : 0;
	}

	@Override
	public InputStream getInputStream(StreamOptions options) throws IOException {
		return getInputStream(0, options);
	}

	@Override
	public InputStream getInputStream(long startingPos, StreamOptions options) throws IOException {
		InputStream in = getInputStream(startingPos);
		int size = bufferSizeIn(options);
		return size > 0 ? new java.io.BufferedInputStream(in, size) : in;
	}

	@Override
	public OutputStream getOutputStream(boolean append, StreamOptions options) throws IOException {
		return openOutput(append, bufferSizeIn(options));
	}

	/**
	 * A read-only stream that can be moved, as a RandomAccessFile opened with "r". A seek costs
	 * nothing until the next read, which starts a range request at the new place (a short step
	 * forward is read through instead). See {@link WebDavSeekableInputStream}.
	 */
	public ISeekableInputStream getSeekableInputStream() throws IOException {
		return openSeekable();
	}

	@Override
	public ISeekableInputStream getSeekableInputStream(StreamOptions options) throws IOException {
		return openSeekable();
	}

	private ISeekableInputStream openSeekable() throws IOException {
		if( !exists() ) {
			throw new FileNotFoundException(getAbsolutePath()+" (No such file or directory)");
		}
		if( isDirectory() ) {
			throw new FileNotFoundException(getAbsolutePath()+" (Is a directory)");
		}
		return new WebDavSeekableInputStream(this);
	}

	/**
	 * Random access in RandomAccessFile's modes. "r" reads in chunks with range requests
	 * ({@link WebDavRandomAccessIoController}). "rw", "rws" and "rwd" create the file if it is missing
	 * and work on a local copy, sent back whole when the stream is synced or closed
	 * ({@link WebDavLocalCopyIoController}): WebDAV can't write part of a file.
	 *
	 * @throws FileNotFoundException if it's a directory, missing in "r" mode, or can't be created
	 */
	@Override
	public IRandomAccessStream getRandomAccessStream(String mode) throws IOException {
		return openRandomAccess(mode, 0);
	}

	/**
	 * {@link #getRandomAccessStream(String)} with a chunk size of its own ({@link StreamOption#CHUNK_SIZE}:
	 * how much one range request reads), else the buffer size, else the factory's.
	 */
	@Override
	public IRandomAccessStream getRandomAccessStream(String mode, StreamOptions options) throws IOException {
		StreamOptions o = StreamOptions.orNone(options);
		Integer chunk = o.get(StreamOption.CHUNK_SIZE);
		return openRandomAccess(mode, chunk != null && chunk > 0 ? chunk : bufferSizeIn(o));
	}

	private IRandomAccessStream openRandomAccess(String mode, int chunkSize) throws IOException {
		IRandomAccessIoController io = "r".equals(mode)
				? new WebDavRandomAccessIoController(this, chunkSize)
				: new WebDavLocalCopyIoController(this, mode);
		try {
			return new FileSourceRandomAccessStream(io, mode);
		} catch (IOException | RuntimeException e) {
			try {
				io.close();
			} catch (Exception e2) {
				e.addSuppressed(e2);
			}
			throw e;
		}
	}

	// ---- links: WebDAV has none

	/** WebDAV has no links: always null. */
	public FileSource getLinkedTo() throws IOException {
		return null;
	}

	// ---- names and paths

	public String getName() {
		return name;
	}

	public String getParent() {
		return parent;
	}

	public FileSource getParentFile() throws IOException {
		if( parentFile == null ) {
			String p = getParent();
			if( p != null && p.length() > 0 ) {
				parentFile = (WebDavFileSource)getFileSourceFactory().createFileSource(p);
			}
		}
		return parentFile;
	}

	public FileSource getChild(String path) throws IOException {
		// relative to this even if it starts with "/"; "." and ".." are resolved
		return new WebDavFileSource(this,path);
	}

	public FileSourceFactory getFileSourceFactory() {
		return factory;
	}

	public String getAbsolutePath() {
		return pathOf(this);
	}

	/**
	 * This file's path with "." and ".." resolved: WebDAV has no links, so this is the path the
	 * server's methods see.
	 */
	public String getCanonicalPath() throws IOException {
		return pathOf(this);
	}

	public String getPath() {
		return pathOf(this);
	}

	public boolean isHidden() {
		return getName().startsWith(".");
	}

	public String getTitle() throws IOException {
		return factory.getTitle()+getCanonicalPath();
	}

	public long getVersion() {
		return 0;
	}

	public long getMaxVersion() {
		return 0;
	}

	public long getVersionDate() throws IOException {
		return lastModified();
	}

	public boolean isVersionSupported() {
		return false;
	}

	public boolean setVersion(long version, boolean saveChange) throws IOException {
		return false;
	}

	public boolean setVersionDate(long time) {
		return false;
	}

	public String toString() {
		return pathOf(this);
	}

	/** Orders by path, then by kind, like the other FileSources. */
	public int compareTo(Object o) {
		String mine = pathOf(this);
		if( o instanceof FileSource ) {
			int ret = mine.compareTo(((FileSource) o).getAbsolutePath());
			return ret != 0 ? ret : getClass().getName().compareTo(o.getClass().getName());
		}
		return mine.compareTo(String.valueOf(o));
	}

	/**
	 * Equal when it is the same path on the same server, root and account, as with the other
	 * FileSources: like java.io.File, two handles for one file are equal.
	 */
	@Override
	public boolean equals(Object obj) {
		if( this == obj ) {
			return true;
		}
		if( !(obj instanceof WebDavFileSource) ) {
			return false;
		}
		WebDavFileSource f = (WebDavFileSource) obj;
		return pathOf(this).equals(pathOf(f)) && factory.isSameFileSystem(f.factory);
	}

	/** Hashes the path, which equals() compares (the same path on two servers just shares a bucket). */
	@Override
	public int hashCode() {
		return pathOf(this).hashCode();
	}

	/**
	 * A filesource: URL with the connection (the password as it was set: a reference stays one),
	 * the type and, when it isn't "/", the root.
	 */
	public URL toURL() throws MalformedURLException {
		String path = pathOf(this);
		String user = factory.getUser();
		if( user == null ) {
			user = "";
		}
		String pw = factory.getPasswd();
		if( pw == null ) {
			pw = "";
		}
		int port = factory.getPort();
		String host = factory.getHost();
		String portStr = port == factory.defaultPort() ? "":":"+port;
		String root = factory.getRoot();
		String login = user.isEmpty() ? "" : user+":"+pw+"@";
		String url = FileSourceFactory.FILE_SOURCE_PROTOCOL+"://"+login+host+portStr+WebDavClient.encodePath(path)
				+"?"+FileSourceFactory.QUERY_STRING_SOURCE_TYPE+"="+factory.getTypeId()
				+(WebDavFileSourceFactory.DEFAULT_ROOT.equals(root) ? "" : "&"+WebDavFileSourceFactory.PROP_ROOT+"="+WebDavClient.encodePath(root));
		return new URL(url);
	}

	/** The path, built from the parent and name: no I/O, and it can't fail. */
	private static String pathOf(WebDavFileSource f) {
		String name = f.name == null ? "" : f.name;
		if( f.parent == null ) {
			return name;
		}
		return f.parent.equals("/") ? "/" + name : f.parent + "/" + name;
	}

	/**
	 * The path in canonical form: absolute, "/"-separated ("\\" counts as "/"), no repeated or
	 * trailing separators, "." dropped and ".." removing the element before it (".." at the root
	 * stays at the root). A relative path is taken from the root;
	 * WebDavFileSourceFactory.createFileSource resolves relative paths first.
	 */
	public static String normalize(String path) {
		Deque<String> parts = new ArrayDeque<>();
		for(String part : (path == null ? "" : path).replace('\\', '/').split("/")) {
			if( part.isEmpty() || part.equals(".")) {
				continue;
			}
			if( part.equals("..")) {
				parts.pollLast();
			} else {
				parts.addLast(part);
			}
		}
		return SEPARATOR+String.join(SEPARATOR, parts);
	}

	/** The folder a normalized path is in; "/" for the root and anything in it. */
	static String parentOf(String path) {
		int idx = path.lastIndexOf(SEPARATOR_CHAR);
		return idx <= 0 ? SEPARATOR : path.substring(0, idx);
	}

	/** Sets parent and name from the normalized path; the root has name "/" and no parent. */
	private void initPath(String path) {
		String p = normalize(path);
		int idx = p.lastIndexOf(SEPARATOR_CHAR);
		if( p.equals(SEPARATOR)) {
			parent = null;
			name = SEPARATOR;
		} else {
			parent = idx == 0 ? SEPARATOR : p.substring(0, idx);
			name = p.substring(idx+1);
		}
	}
}
