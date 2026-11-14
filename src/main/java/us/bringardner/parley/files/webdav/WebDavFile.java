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

/**
 * What a WebDAV server said of one resource in a PROPFIND answer (RFC 4918 section 15): whether it
 * is a collection (a folder), its length, its times, its entity tag. Immutable: a change is a new
 * PROPFIND.
 */
final class WebDavFile {

	/** The resource's path, from the root setting: "/" for the root, "/a/b.txt" below it. */
	private final String path;
	private final boolean directory;
	/** DAV:getcontentlength; 0 for a collection or when the server doesn't say. */
	private final long length;
	/** DAV:getlastmodified (whole seconds); 0 when the server doesn't say. */
	private final long lastModified;
	/** DAV:creationdate; 0 when the server doesn't say. */
	private final long created;
	/** DAV:getetag; null when the server doesn't say. */
	private final String etag;
	/** DAV:getcontenttype; null when the server doesn't say. */
	private final String contentType;

	WebDavFile(String path, boolean directory, long length, long lastModified, long created, String etag, String contentType) {
		this.path = path;
		this.directory = directory;
		this.length = directory ? 0 : Math.max(0, length);
		this.lastModified = lastModified;
		this.created = created;
		this.etag = etag;
		this.contentType = contentType;
	}

	String getPath() {
		return path;
	}

	/** The last part of the path; "/" for the root. */
	String getName() {
		if( path.equals("/") ) {
			return "/";
		}
		return path.substring(path.lastIndexOf('/') + 1);
	}

	boolean isDirectory() {
		return directory;
	}

	boolean isFile() {
		return !directory;
	}

	long getLength() {
		return length;
	}

	long getLastModified() {
		return lastModified;
	}

	long getCreated() {
		return created;
	}

	String getEtag() {
		return etag;
	}

	String getContentType() {
		return contentType;
	}

	@Override
	public String toString() {
		return (directory ? "d " : "- ") + length + " " + path;
	}
}
