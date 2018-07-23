package org.codice.ddf.security.session;

import org.eclipse.jetty.server.session.SessionDataStore;
import org.eclipse.jetty.server.session.SessionDataStoreFactory;
import org.eclipse.jetty.server.session.SessionHandler;

public class SingletonInMemorySessionDataStoreFactory implements SessionDataStoreFactory {
  private SingletonInMemorySessionDataStore singletonInMemorySessionDataStore;

  @Override
  public SessionDataStore getSessionDataStore(SessionHandler handler) throws Exception {
    synchronized (this) {
      if (singletonInMemorySessionDataStore == null
          || singletonInMemorySessionDataStore.isDestroyed())
        singletonInMemorySessionDataStore = new SingletonInMemorySessionDataStore();
      return singletonInMemorySessionDataStore;
    }
  }
}
