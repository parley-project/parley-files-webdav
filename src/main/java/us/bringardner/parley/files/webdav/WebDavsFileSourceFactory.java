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
 * FileSource factory for WebDAV servers over HTTPS (the webdavs type, port 443 unless another is
 * given). The server's certificate is checked as Parley's other clients check one: the chain by the
 * application's trust managers ({@code SecureBaseObject.setDefaultTrustManagers()}) when it has set
 * them, else by the JVM's trust store, and it must be for the host connected to.
 */
public class WebDavsFileSourceFactory extends WebDavFileSourceFactory {

	private static final long serialVersionUID = 1L;
	public static final String FACTORY_ID = "webdavs";

	public WebDavsFileSourceFactory() {
		super();
	}

	/** Always: a webdavs connection is HTTPS. */
	@Override
	public boolean isSecure() {
		return true;
	}

	@Override
	protected WebDavFileSourceFactory newCopy() {
		return new WebDavsFileSourceFactory();
	}

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}
}
